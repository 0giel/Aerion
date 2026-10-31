//! Minecraft's NBT format: enough to read `level.dat` (gzip) and read/write `servers.dat`
//! (uncompressed) while keeping every tag we don't understand.

use std::io::Read;

use crate::error::{Error, Result};

#[derive(Debug, Clone, PartialEq)]
pub enum Tag {
    Byte(i8),
    Short(i16),
    Int(i32),
    Long(i64),
    Float(f32),
    Double(f64),
    ByteArray(Vec<i8>),
    String(String),
    /// Element type id and elements (type is kept for empty lists).
    List(u8, Vec<Tag>),
    /// Ordered, so files are written back in their original order.
    Compound(Vec<(String, Tag)>),
    IntArray(Vec<i32>),
    LongArray(Vec<i64>),
}

impl Tag {
    fn id(&self) -> u8 {
        match self {
            Tag::Byte(_) => 1,
            Tag::Short(_) => 2,
            Tag::Int(_) => 3,
            Tag::Long(_) => 4,
            Tag::Float(_) => 5,
            Tag::Double(_) => 6,
            Tag::ByteArray(_) => 7,
            Tag::String(_) => 8,
            Tag::List(..) => 9,
            Tag::Compound(_) => 10,
            Tag::IntArray(_) => 11,
            Tag::LongArray(_) => 12,
        }
    }

    pub fn get(&self, key: &str) -> Option<&Tag> {
        match self {
            Tag::Compound(items) => items.iter().find(|(k, _)| k == key).map(|(_, v)| v),
            _ => None,
        }
    }

    pub fn as_str(&self) -> Option<&str> {
        match self {
            Tag::String(s) => Some(s),
            _ => None,
        }
    }

    pub fn as_i64(&self) -> Option<i64> {
        match *self {
            Tag::Byte(v) => Some(v.into()),
            Tag::Short(v) => Some(v.into()),
            Tag::Int(v) => Some(v.into()),
            Tag::Long(v) => Some(v),
            _ => None,
        }
    }
}

struct Reader<'a> {
    data: &'a [u8],
    pos: usize,
    depth: u32,
}

impl Reader<'_> {
    fn take(&mut self, n: usize) -> Result<&[u8]> {
        let end = self.pos.checked_add(n).filter(|&e| e <= self.data.len());
        let end = end.ok_or_else(|| Error::parse("NBT", "unexpected end of data"))?;
        let s = &self.data[self.pos..end];
        self.pos = end;
        Ok(s)
    }
    fn u8(&mut self) -> Result<u8> {
        Ok(self.take(1)?[0])
    }
    fn i16(&mut self) -> Result<i16> {
        Ok(i16::from_be_bytes(self.take(2)?.try_into().unwrap()))
    }
    fn i32(&mut self) -> Result<i32> {
        Ok(i32::from_be_bytes(self.take(4)?.try_into().unwrap()))
    }
    fn i64(&mut self) -> Result<i64> {
        Ok(i64::from_be_bytes(self.take(8)?.try_into().unwrap()))
    }
    /// Element count of an array or list whose elements take at least `size` bytes each. A
    /// count larger than the remaining data is corrupt, not a reason for a huge allocation.
    fn len(&mut self, size: usize) -> Result<usize> {
        let n = self.i32()?;
        usize::try_from(n)
            .ok()
            .filter(|&n| n.saturating_mul(size) <= self.data.len() - self.pos)
            .ok_or_else(|| Error::parse("NBT", "invalid length"))
    }
    fn string(&mut self) -> Result<String> {
        let n = self.i16()? as u16 as usize;
        // Java's modified UTF-8 is plain UTF-8 for everything a player can type.
        Ok(String::from_utf8_lossy(self.take(n)?).into_owned())
    }
    fn payload(&mut self, id: u8) -> Result<Tag> {
        Ok(match id {
            1 => Tag::Byte(self.u8()? as i8),
            2 => Tag::Short(self.i16()?),
            3 => Tag::Int(self.i32()?),
            4 => Tag::Long(self.i64()?),
            5 => Tag::Float(f32::from_bits(self.i32()? as u32)),
            6 => Tag::Double(f64::from_bits(self.i64()? as u64)),
            7 => {
                let n = self.len(1)?;
                Tag::ByteArray(self.take(n)?.iter().map(|&b| b as i8).collect())
            }
            8 => Tag::String(self.string()?),
            9 => {
                self.depth += 1;
                if self.depth > 512 {
                    return Err(Error::parse("NBT", "nested too deeply"));
                }
                let kind = self.u8()?;
                // Compound/end elements can be a single byte.
                let n = self.len(1)?;
                let items = (0..n)
                    .map(|_| self.payload(kind))
                    .collect::<Result<Vec<_>>>()?;
                self.depth -= 1;
                Tag::List(kind, items)
            }
            10 => {
                self.depth += 1;
                if self.depth > 512 {
                    return Err(Error::parse("NBT", "nested too deeply"));
                }
                let mut items = Vec::new();
                loop {
                    let kind = self.u8()?;
                    if kind == 0 {
                        break;
                    }
                    let name = self.string()?;
                    items.push((name, self.payload(kind)?));
                }
                self.depth -= 1;
                Tag::Compound(items)
            }
            11 => {
                let n = self.len(4)?;
                Tag::IntArray((0..n).map(|_| self.i32()).collect::<Result<_>>()?)
            }
            12 => {
                let n = self.len(8)?;
                Tag::LongArray((0..n).map(|_| self.i64()).collect::<Result<_>>()?)
            }
            0 if self.depth > 0 => Tag::Compound(vec![]),
            other => return Err(Error::parse("NBT", format!("unknown tag {other}"))),
        })
    }
}

/// Parse an uncompressed NBT document (root compound).
pub fn read(data: &[u8]) -> Result<Tag> {
    let mut r = Reader {
        data,
        pos: 0,
        depth: 0,
    };
    if r.u8()? != 10 {
        return Err(Error::parse("NBT", "root is not a compound"));
    }
    r.string()?;
    r.payload(10)
}

/// Parse a gzip-compressed NBT document (`level.dat`), at most 64 MiB uncompressed.
pub fn read_gzip(data: &[u8]) -> Result<Tag> {
    let mut out = Vec::new();
    flate2::read::GzDecoder::new(data)
        .take(64 << 20)
        .read_to_end(&mut out)
        .map_err(|e| Error::parse("NBT", e))?;
    read(&out)
}

fn write_payload(out: &mut Vec<u8>, tag: &Tag) {
    let string = |out: &mut Vec<u8>, s: &str| {
        let b = s.as_bytes();
        let n = b.len().min(u16::MAX as usize);
        out.extend((n as u16).to_be_bytes());
        out.extend(&b[..n]);
    };
    match tag {
        Tag::Byte(v) => out.push(*v as u8),
        Tag::Short(v) => out.extend(v.to_be_bytes()),
        Tag::Int(v) => out.extend(v.to_be_bytes()),
        Tag::Long(v) => out.extend(v.to_be_bytes()),
        Tag::Float(v) => out.extend(v.to_bits().to_be_bytes()),
        Tag::Double(v) => out.extend(v.to_bits().to_be_bytes()),
        Tag::ByteArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            out.extend(v.iter().map(|&b| b as u8));
        }
        Tag::String(s) => string(out, s),
        Tag::List(kind, items) => {
            out.push(if items.is_empty() {
                *kind
            } else {
                items[0].id()
            });
            out.extend((items.len() as i32).to_be_bytes());
            for i in items {
                write_payload(out, i);
            }
        }
        Tag::Compound(items) => {
            for (k, v) in items {
                out.push(v.id());
                string(out, k);
                write_payload(out, v);
            }
            out.push(0);
        }
        Tag::IntArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            for x in v {
                out.extend(x.to_be_bytes());
            }
        }
        Tag::LongArray(v) => {
            out.extend((v.len() as i32).to_be_bytes());
            for x in v {
                out.extend(x.to_be_bytes());
            }
        }
    }
}

/// Serialize a root compound (uncompressed, empty root name).
pub fn write(root: &Tag) -> Vec<u8> {
    let mut out = vec![10, 0, 0];
    write_payload(&mut out, root);
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn round_trip_keeps_unknown_tags() {
        let root = Tag::Compound(vec![
            (
                "servers".into(),
                Tag::List(
                    10,
                    vec![Tag::Compound(vec![
                        ("name".into(), Tag::String("Mój serwer".into())),
                        ("ip".into(), Tag::String("mc.example.com:25566".into())),
                        ("acceptTextures".into(), Tag::Byte(1)),
                        ("weird".into(), Tag::LongArray(vec![1, -2])),
                    ])],
                ),
            ),
            ("empty".into(), Tag::List(8, vec![])),
            ("f".into(), Tag::Double(1.5)),
        ]);
        let bytes = write(&root);
        assert_eq!(read(&bytes).unwrap(), root);
        let s = &read(&bytes).unwrap();
        let first = match s.get("servers") {
            Some(Tag::List(_, l)) => &l[0],
            _ => panic!(),
        };
        assert_eq!(first.get("name").and_then(Tag::as_str), Some("Mój serwer"));
    }

    #[test]
    fn rejects_garbage() {
        assert!(read(&[]).is_err());
        assert!(read(&[10, 0, 0, 7, 0, 1, b'a', 0x7f, 0xff, 0xff, 0xff]).is_err());
        assert!(read(&[3, 0, 0]).is_err());
    }
}
