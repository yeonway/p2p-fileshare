#![windows_subsystem = "windows"]

use std::{
    env, fs,
    path::{Path, PathBuf},
    process::{Command, Stdio},
    thread,
    time::Duration,
};

const INSTALLER_NAME: &str = "윈도우_설치하기.exe";
const APP_EXE_NAMES: [&str; 2] = ["sand honey where.exe", "p2p-fileshare-windows.exe"];

fn main() {
    let launcher_dir = env::current_exe()
        .ok()
        .and_then(|path| path.parent().map(Path::to_path_buf))
        .unwrap_or_else(|| PathBuf::from("."));
    let installer = launcher_dir.join(INSTALLER_NAME);

    if installer.exists() {
        let _ = Command::new(&installer)
            .arg("/S")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::null())
            .status();
    }

    for _ in 0..40 {
        if let Some(app) = find_installed_app() {
            let _ = Command::new(app).spawn();
            return;
        }
        thread::sleep(Duration::from_millis(500));
    }

    if installer.exists() {
        let _ = Command::new(installer).spawn();
    }
}

fn find_installed_app() -> Option<PathBuf> {
    exact_candidates()
        .into_iter()
        .find(|path| path.is_file())
        .or_else(search_likely_install_roots)
}

fn exact_candidates() -> Vec<PathBuf> {
    let mut paths = Vec::new();
    for root_var in ["LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)"] {
        if let Some(root) = env::var_os(root_var).map(PathBuf::from) {
            for app_dir in ["sand honey where", "p2p-fileshare-windows"] {
                for exe_name in APP_EXE_NAMES {
                    paths.push(root.join(app_dir).join(exe_name));
                    paths.push(root.join("Programs").join(app_dir).join(exe_name));
                }
            }
        }
    }
    paths
}

fn search_likely_install_roots() -> Option<PathBuf> {
    let roots = ["LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)"]
        .into_iter()
        .filter_map(|var| env::var_os(var).map(PathBuf::from))
        .flat_map(|root| [root.join("Programs"), root]);

    for root in roots {
        if let Some(path) = search_limited(&root, 4) {
            return Some(path);
        }
    }
    None
}

fn search_limited(root: &Path, max_depth: usize) -> Option<PathBuf> {
    if max_depth == 0 || !root.is_dir() {
        return None;
    }
    let entries = fs::read_dir(root).ok()?;
    for entry in entries.flatten() {
        let path = entry.path();
        if path.is_file() {
            let name = path.file_name()?.to_string_lossy();
            if APP_EXE_NAMES.iter().any(|candidate| name.eq_ignore_ascii_case(candidate)) {
                return Some(path);
            }
        } else if path.is_dir() {
            let dir_name = path
                .file_name()
                .map(|name| name.to_string_lossy().to_lowercase())
                .unwrap_or_default();
            if dir_name.contains("honey") || dir_name.contains("p2p") || dir_name.contains("sand") {
                if let Some(found) = search_limited(&path, max_depth - 1) {
                    return Some(found);
                }
            }
        }
    }
    None
}
