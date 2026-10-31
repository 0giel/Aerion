//! Hashing: SHA-1/256/512 for downloads, MurmurHash2 for CurseForge fingerprints.

use std::io::Read;
use std::path::Path;

use sha1::Digest;

use crate::error::{Error, IoContext, Result};

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Hash {
    Sha1(String),
    Sha256(String),
    Sha512(String),
}

impl Hash {
    pub fn expected(&self) -> &str {
        match self {
            Hash::Sha1(h) | Hash::Sha256(h) | Hash::Sha512(h) => h,
        }
    }

    pub fn hasher(&self) -> Hasher {
        match self {
            Hash::Sha1(_) => Hasher::Sha1(sha1::Sha1::new()),
            Hash::Sha256(_) => Hasher::Sha256(sha2::Sha256::new()),
            Hash::Sha512(_) => Hasher::Sha512(sha2::Sha512::new()),
        }
    }
}

pub enum Hasher {
    Sha1(sha1::Sha1),
    Sha256(sha2::Sha256),
    Sha512(sha2::Sha512),
}

impl Hasher {
    pub fn update(&mut self, data: &[u8]) {
        match self {
            Hasher::Sha1(h) => h.update(data),
            Hasher::Sha256(h) => h.update(data),
            Hasher::Sha512(h) => h.update(data),
        }
    }

    pub fn finish_hex(self) -> String {
        match self {
            Hasher::Sha1(h) => hex::encode(h.finalize()),
            Hasher::Sha256(h) => hex::encode(h.finalize()),
            Hasher::Sha512(h) => hex::encode(h.finalize()),
        }
    }
}

/// Hash a file on a blocking thread.
pub async fn hash_file(path: &Path, algo: &Hash) -> Result<String> {
    let path = path.to_path_buf();
    let mut hasher = algo.hasher();
    tokio::task::spawn_blocking(move || {
        let mut file = std::fs::File::open(&path).at(&path)?;
        let mut buf = vec![0u8; 256 * 1024];
        loop {
            let n = file.read(&mut buf).at(&path)?;
            if n == 0 {
                break;
            }
            hasher.update(&buf[..n]);
        }
        Ok(hasher.finish_hex())
    })
    .await
    .map_err(|e| Error::Invalid(format!("hash task failed: {e}")))?
}

pub async fn sha1_file(path: &Path) -> Result<String> {
    hash_file(path, &Hash::Sha1(String::new())).await
}

pub async fn sha512_file(path: &Path) -> Result<String> {
    hash_file(path, &Hash::Sha512(String::new())).await
}

pub async fn verify_file(path: &Path, expected: &Hash) -> Result<bool> {
    let actual = hash_file(path, expected).await?;
    Ok(actual.eq_ignore_ascii_case(expected.expected()))
}

pub fn sha1_hex(data: &[u8]) -> String {
    hex::encode(sha1::Sha1::digest(data))
}

/// CurseForge file fingerprint: MurmurHash2 (seed 1) of the file with all
/// whitespace bytes (tab, LF, CR, space) removed.
pub fn curseforge_fingerprint(data: &[u8]) -> u32 {
    let normalized: Vec<u8> = data
        .iter()
        .copied()
        .filter(|b| !matches!(b, 9 | 10 | 13 | 32))
        .collect();
    murmur2(&normalized, 1)
}

pub async fn curseforge_fingerprint_file(path: &Path) -> Result<u32> {
    let bytes = tokio::fs::read(path).await.at(path)?;
    tokio::task::spawn_blocking(move || curseforge_fingerprint(&bytes))
        .await
        .map_err(|e| Error::Invalid(format!("fingerprint task failed: {e}")))
}

/// 32-bit MurmurHash2 (Austin Appleby), little-endian block reads.
pub fn murmur2(data: &[u8], seed: u32) -> u32 {
    const M: u32 = 0x5bd1_e995;
    const R: u32 = 24;
    let len = data.len();
    let mut h: u32 = seed ^ (len as u32);
    let (chunks, rest) = data.as_chunks::<4>();
    for chunk in chunks {
        let mut k = u32::from_le_bytes(*chunk);
        k = k.wrapping_mul(M);
        k ^= k >> R;
        k = k.wrapping_mul(M);
        h = h.wrapping_mul(M);
        h ^= k;
    }
    if rest.len() >= 3 {
        h ^= u32::from(rest[2]) << 16;
    }
    if rest.len() >= 2 {
        h ^= u32::from(rest[1]) << 8;
    }
    if !rest.is_empty() {
        h ^= u32::from(rest[0]);
        h = h.wrapping_mul(M);
    }
    h ^= h >> 13;
    h = h.wrapping_mul(M);
    h ^= h >> 15;
    h
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sha1_known_vector() {
        assert_eq!(sha1_hex(b"abc"), "a9993e364706816aba3e25717850c26c9cd0d89d");
    }

    #[test]
    fn murmur2_matches_reference_implementation() {
        // Values computed with a direct port of the reference C implementation (MurmurHash2.cpp).
        assert_eq!(murmur2(b"", 0), 0);
        assert_eq!(murmur2(b"", 1), 0x5bd1_5e36);
        assert_eq!(murmur2(b"a", 1), 0x2550_b18c);
        assert_eq!(murmur2(b"abcd", 1), 0xc93f_7a16);
        assert_eq!(murmur2(b"hello world", 1), 0x83ea_5dee);
    }

    #[test]
    fn fingerprint_ignores_whitespace() {
        assert_eq!(
            curseforge_fingerprint(b"a b\r\n\tc"),
            curseforge_fingerprint(b"abc")
        );
    }

    #[tokio::test]
    async fn hash_file_streams() {
        let dir = tempfile::tempdir().unwrap();
        let p = dir.path().join("f.bin");
        tokio::fs::write(&p, b"abc").await.unwrap();
        assert!(
            verify_file(
                &p,
                &Hash::Sha1("A9993E364706816ABA3E25717850C26C9CD0D89D".into())
            )
            .await
            .unwrap()
        );
        let sha512 = sha512_file(&p).await.unwrap();
        assert!(sha512.starts_with("ddaf35a193617aba"));
    }
}
