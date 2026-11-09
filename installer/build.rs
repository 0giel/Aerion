//! The logo as raw pixels for the window, and the icon and manifest for the program file.
use std::{env, fs, fs::File, path::Path};

fn main() {
    let source = "../src-tauri/icons/128x128.png";
    println!("cargo:rerun-if-changed={source}");
    println!("cargo:rerun-if-changed=app.rc");
    println!("cargo:rerun-if-changed=app.manifest");
    let mut decoder = png::Decoder::new(File::open(source).expect("the logo"));
    decoder.set_transformations(
        png::Transformations::normalize_to_color8() | png::Transformations::ALPHA,
    );
    let mut reader = decoder.read_info().expect("the logo's header");
    let mut pixels = vec![0; reader.output_buffer_size()];
    let info = reader.next_frame(&mut pixels).expect("the logo's pixels");
    assert_eq!(
        (info.width, info.height),
        (128, 128),
        "the window expects a 128x128 logo"
    );
    pixels.truncate(info.buffer_size());
    assert_eq!(pixels.len(), 128 * 128 * 4, "the logo must decode to RGBA");
    let out = env::var("OUT_DIR").unwrap();
    fs::write(Path::new(&out).join("logo.rgba"), pixels).unwrap();
    if env::var("CARGO_CFG_WINDOWS").is_ok() {
        embed_resource::compile("app.rc", embed_resource::NONE)
            .manifest_required()
            .expect("the icon and manifest");
    }
}
