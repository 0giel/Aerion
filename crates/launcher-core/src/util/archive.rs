//! Archive extraction with zip-slip protection.

use std::io::{Read, Write};
use std::path::{Path, PathBuf};

use crate::error::{Error, IoContext, Result};
use crate::util::fs::safe_join;

/// Extract the entries `target` maps to a relative path (others are skipped), returning the
/// written files. Every path goes through [`safe_join`], so `../` and absolute paths are rejected.
fn extract(
    archive: &Path,
    dest: &Path,
    target: &dyn Fn(&str) -> Option<String>,
) -> Result<Vec<PathBuf>> {
    let file = std::fs::File::open(archive).at(archive)?;
    let mut zip = zip::ZipArchive::new(std::io::BufReader::new(file))?;
    let mut written = Vec::new();
    for i in 0..zip.len() {
        let mut entry = zip.by_index(i)?;
        let name = entry.name()?.replace('\\', "/");
        let Some(rel) = target(&name) else {
            continue;
        };
        let rel = rel.trim_end_matches('/');
        if rel.is_empty() {
            continue;
        }
        let out = safe_join(dest, rel)?;
        if entry.is_dir() {
            std::fs::create_dir_all(&out).at(&out)?;
            continue;
        }
        if let Some(parent) = out.parent() {
            std::fs::create_dir_all(parent).at(parent)?;
        }
        let mut f = std::fs::File::create(&out).at(&out)?;
        std::io::copy(&mut entry, &mut f).at(&out)?;
        f.flush().at(&out)?;
        #[cfg(unix)]
        if let Some(mode) = entry.unix_mode()
            && mode & 0o111 != 0
        {
            use std::os::unix::fs::PermissionsExt;
            let _ = std::fs::set_permissions(&out, std::fs::Permissions::from_mode(0o755));
        }
        written.push(out);
    }
    Ok(written)
}

/// Extract the entries whose names pass `filter`, returning the written files.
pub fn extract_zip(
    archive: &Path,
    dest: &Path,
    filter: &dyn Fn(&str) -> bool,
) -> Result<Vec<PathBuf>> {
    extract(archive, dest, &|name| filter(name).then(|| name.to_owned()))
}

/// Extract everything under `prefix` (e.g. `overrides/`) into `dest`, stripping the prefix.
pub fn extract_zip_prefix(archive: &Path, prefix: &str, dest: &Path) -> Result<usize> {
    let written = extract(archive, dest, &|name| {
        name.strip_prefix(prefix).map(str::to_owned)
    })?;
    Ok(written.len())
}

/// Read a single entry into memory (`None` if absent).
pub fn read_zip_entry(archive: &Path, name: &str) -> Result<Option<Vec<u8>>> {
    let file = std::fs::File::open(archive).at(archive)?;
    let mut zip = zip::ZipArchive::new(std::io::BufReader::new(file))?;
    let name = name.trim_start_matches('/');
    let Ok(mut entry) = zip.by_name(name) else {
        return Ok(None);
    };
    let mut buf = Vec::with_capacity(entry.size() as usize);
    entry.read_to_end(&mut buf).at(archive)?;
    Ok(Some(buf))
}

pub fn list_zip_entries(archive: &Path) -> Result<Vec<String>> {
    let file = std::fs::File::open(archive).at(archive)?;
    let zip = zip::ZipArchive::new(std::io::BufReader::new(file))?;
    Ok(zip
        .file_names()
        .map(|n| n.map(|s| s.into_owned()))
        .collect::<Result<Vec<_>, _>>()?)
}

/// Copy one entry to a file on disk. Returns `false` when the entry does not exist.
pub fn extract_zip_entry(archive: &Path, name: &str, out: &Path) -> Result<bool> {
    let file = std::fs::File::open(archive).at(archive)?;
    let mut zip = zip::ZipArchive::new(std::io::BufReader::new(file))?;
    let Ok(mut entry) = zip.by_name(name.trim_start_matches('/')) else {
        return Ok(false);
    };
    if let Some(parent) = out.parent() {
        std::fs::create_dir_all(parent).at(parent)?;
    }
    let tmp = out.with_extension("extracting");
    let mut f = std::fs::File::create(&tmp).at(&tmp)?;
    std::io::copy(&mut entry, &mut f).at(&tmp)?;
    drop(f);
    std::fs::rename(&tmp, out).at(out)?;
    Ok(true)
}

/// Extract a `.tar.gz` (Adoptium JREs on Linux/macOS). `tar::Entry::unpack_in` refuses paths
/// that would escape `dest`.
pub fn extract_tar_gz(archive: &Path, dest: &Path) -> Result<()> {
    let file = std::fs::File::open(archive).at(archive)?;
    let mut tar = tar::Archive::new(flate2::read::GzDecoder::new(std::io::BufReader::new(file)));
    tar.set_preserve_permissions(true);
    std::fs::create_dir_all(dest).at(dest)?;
    for entry in tar.entries().at(archive)? {
        let mut entry = entry.at(archive)?;
        let path = entry.path().at(archive)?.display().to_string();
        if !entry.unpack_in(dest).at(dest)? {
            return Err(Error::UnsafePath(path));
        }
    }
    Ok(())
}

/// Builder for writing zip files (exports).
pub struct ZipBuilder {
    zip: zip::ZipWriter<std::io::BufWriter<std::fs::File>>,
    path: PathBuf,
}

impl ZipBuilder {
    pub fn create(path: &Path) -> Result<Self> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent).at(parent)?;
        }
        let file = std::fs::File::create(path).at(path)?;
        Ok(Self {
            zip: zip::ZipWriter::new(std::io::BufWriter::new(file)),
            path: path.to_path_buf(),
        })
    }

    fn options() -> zip::write::SimpleFileOptions {
        zip::write::SimpleFileOptions::default()
            .compression_method(zip::CompressionMethod::Deflated)
    }

    pub fn add_bytes(&mut self, name: &str, data: &[u8]) -> Result<()> {
        self.zip.start_file(name, Self::options())?;
        self.zip.write_all(data).at(&self.path)
    }

    pub fn add_file(&mut self, name: &str, src: &Path) -> Result<()> {
        self.zip.start_file(name, Self::options())?;
        let mut f = std::fs::File::open(src).at(src)?;
        std::io::copy(&mut f, &mut self.zip).at(src)?;
        Ok(())
    }

    /// Add a directory tree under `prefix/`, skipping paths for which `skip` returns true.
    pub fn add_dir(
        &mut self,
        prefix: &str,
        dir: &Path,
        skip: &dyn Fn(&Path) -> bool,
    ) -> Result<()> {
        let mut stack = vec![dir.to_path_buf()];
        while let Some(current) = stack.pop() {
            for entry in std::fs::read_dir(&current).at(&current)? {
                let entry = entry.at(&current)?;
                let p = entry.path();
                if skip(&p) {
                    continue;
                }
                if entry.file_type().at(&p)?.is_dir() {
                    stack.push(p);
                } else {
                    let rel = p
                        .strip_prefix(dir)
                        .map_err(|_| Error::UnsafePath(p.display().to_string()))?;
                    let rel = rel.to_string_lossy().replace('\\', "/");
                    let name = if prefix.is_empty() {
                        rel
                    } else {
                        format!("{prefix}/{rel}")
                    };
                    self.add_file(&name, &p)?;
                }
            }
        }
        Ok(())
    }

    pub fn finish(self) -> Result<()> {
        let mut w = self.zip.finish()?;
        w.flush().at(&self.path)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn make_zip(path: &Path, entries: &[(&str, &[u8])]) {
        let file = std::fs::File::create(path).unwrap();
        let mut zip = zip::ZipWriter::new(file);
        for (name, data) in entries {
            zip.start_file(*name, zip::write::SimpleFileOptions::default())
                .unwrap();
            zip.write_all(data).unwrap();
        }
        zip.finish().unwrap();
    }

    #[test]
    fn extracts_and_blocks_zip_slip() {
        let dir = tempfile::tempdir().unwrap();
        let good = dir.path().join("good.zip");
        make_zip(&good, &[("a/b.txt", b"hi"), ("META-INF/MANIFEST.MF", b"x")]);
        let out = dir.path().join("out dir ł");
        let files = extract_zip(&good, &out, &|n| !n.starts_with("META-INF/")).unwrap();
        assert_eq!(files.len(), 1);
        assert_eq!(std::fs::read(out.join("a/b.txt")).unwrap(), b"hi");

        let evil = dir.path().join("evil.zip");
        make_zip(&evil, &[("../escape.txt", b"pwned")]);
        assert!(matches!(
            extract_zip(&evil, &out, &|_| true),
            Err(Error::UnsafePath(_))
        ));
        assert!(!dir.path().join("escape.txt").exists());
    }

    #[test]
    fn reads_single_entry() {
        let dir = tempfile::tempdir().unwrap();
        let z = dir.path().join("x.zip");
        make_zip(&z, &[("install_profile.json", b"{}")]);
        assert_eq!(
            read_zip_entry(&z, "/install_profile.json")
                .unwrap()
                .unwrap(),
            b"{}"
        );
        assert!(read_zip_entry(&z, "missing").unwrap().is_none());
    }
}
