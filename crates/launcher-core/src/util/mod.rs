pub mod archive;
pub mod fs;
pub mod hash;
pub mod image;
pub mod jwt;
pub mod names;
pub mod nbt;
pub mod process;
pub mod sys;

pub fn now_secs() -> i64 {
    chrono::Utc::now().timestamp()
}

pub fn now_millis() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

/// UNIX seconds of a file time (0 before 1970).
pub fn unix_secs(t: std::time::SystemTime) -> i64 {
    t.duration_since(std::time::UNIX_EPOCH)
        .map_or(0, |d| d.as_secs() as i64)
}
