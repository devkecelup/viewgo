fn main() {
    // Embed the brand icon into the exe (Explorer, taskbar, Alt-Tab). rc.exe from the Windows
    // SDK does the embedding; icon.ico is the committed brand asset.
    println!("cargo:rerun-if-changed=icon.ico");
    #[cfg(windows)]
    {
        if let Err(error) = winres::WindowsResource::new()
            .set_icon("icon.ico")
            .compile()
        {
            panic!("embedding icon.ico failed: {error}");
        }
    }
}
