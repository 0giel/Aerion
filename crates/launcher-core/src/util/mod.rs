pub mod archive;
pub mod fs;
pub mod hash;
pub mod image;
pub mod jwt;
pub mod names;
pub mod sys;

/// Current UNIX time in seconds.
pub fn now_secs() -> i64 {
    chrono::Utc::now().timestamp()
}
