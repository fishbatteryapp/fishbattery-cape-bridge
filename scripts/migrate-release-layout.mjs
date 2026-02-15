#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { execFileSync } from "node:child_process";

const repo = process.env.CAPE_BRIDGE_REPO || "fishbatteryapp/fishbattery-cape-bridge";
const deleteOld = process.argv.includes("--delete-old");
const dryRun = process.argv.includes("--dry-run");

const OLD_TAG_RE = /^v(?<mc>\d+\.\d+(?:\.\d+)?)-(?<mod>[0-9A-Za-z][0-9A-Za-z.+-]*)-(?<loader>fabric|quilt)$/;
const JAR_PREFIX = "fishbattery-cape-bridge-";

function ghJson(args) {
  const out = execFileSync("gh", args, { encoding: "utf8" }).trim();
  return out ? JSON.parse(out) : null;
}

function gh(args) {
  execFileSync("gh", args, { stdio: "inherit" });
}

function log(line) {
  process.stdout.write(`${line}\n`);
}

function listAllReleases() {
  const out = [];
  let page = 1;
  while (true) {
    const batch = ghJson(["api", `repos/${repo}/releases?per_page=100&page=${page}`]);
    if (!Array.isArray(batch) || !batch.length) break;
    out.push(...batch);
    if (batch.length < 100) break;
    page += 1;
  }
  return out;
}

function ensureRelease(tag, title, notes) {
  try {
    gh(["release", "view", tag, "--repo", repo]);
  } catch {
    if (dryRun) {
      log(`[dry-run] would create release ${tag}`);
      return;
    }
    gh(["release", "create", tag, "--repo", repo, "--title", title, "--notes", notes]);
  }
}

function main() {
  const releases = listAllReleases().filter((r) => !r?.draft);
  const groups = new Map();

  for (const rel of releases) {
    const tag = String(rel?.tag_name || "").trim();
    const match = tag.match(OLD_TAG_RE);
    if (!match?.groups) continue;
    const mc = match.groups.mc;
    const modVersion = match.groups.mod;
    const loader = match.groups.loader;
    const key = `${modVersion}|${loader}`;
    if (!groups.has(key)) groups.set(key, { modVersion, loader, oldTags: new Set(), assets: [] });

    const group = groups.get(key);
    group.oldTags.add(tag);
    const assets = Array.isArray(rel?.assets) ? rel.assets : [];
    for (const asset of assets) {
      const name = String(asset?.name || "");
      if (!name.startsWith(JAR_PREFIX) || !name.endsWith(".jar")) continue;
      if (!name.toLowerCase().endsWith(`-${mc}-${loader}.jar`)) continue;
      group.assets.push({ tag, name });
    }
  }

  if (!groups.size) {
    log("No legacy releases found to migrate.");
    return;
  }

  for (const group of groups.values()) {
    const newTag = `v${group.modVersion}-${group.loader}`;
    const title = newTag;
    const notes = `Consolidated ${group.loader} release for version ${group.modVersion}. Includes all supported Minecraft targets.`;
    const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), `fb-cape-migrate-${group.modVersion}-${group.loader}-`));

    log(`\nMigrating -> ${newTag} (${group.assets.length} assets)`);
    ensureRelease(newTag, title, notes);

    const seenNames = new Set();
    for (const entry of group.assets) {
      if (seenNames.has(entry.name)) continue;
      seenNames.add(entry.name);
      if (dryRun) {
        log(`[dry-run] would download ${entry.name} from ${entry.tag}`);
        continue;
      }
      gh(["release", "download", entry.tag, "--repo", repo, "--dir", tempDir, "--pattern", entry.name]);
    }

    if (!dryRun) {
      const files = fs
        .readdirSync(tempDir)
        .filter((f) => f.endsWith(".jar"))
        .map((f) => path.join(tempDir, f));
      for (const file of files) {
        gh(["release", "upload", newTag, file, "--repo", repo, "--clobber"]);
      }
    }

    if (deleteOld) {
      for (const oldTag of group.oldTags) {
        if (!OLD_TAG_RE.test(oldTag)) continue;
        if (dryRun) {
          log(`[dry-run] would delete old release ${oldTag}`);
          continue;
        }
        gh(["release", "delete", oldTag, "--repo", repo, "--yes", "--cleanup-tag"]);
      }
    }

    try {
      fs.rmSync(tempDir, { recursive: true, force: true });
    } catch {}
  }

  log("\nRelease migration complete.");
}

main();

