package com.fongmi.android.tv.update;

import java.util.ArrayList;
import java.util.List;

public final class UpdateRoutePlanner {

    private UpdateRoutePlanner() {
    }

    public static List<UpdateTarget> plan(String source, String githubUrl, OciArtifact artifact, GithubProxy.Config githubProxy, String ociEndpoint) {
        List<UpdateTarget> routes = new ArrayList<>();
        String normalized = UpdateSource.normalize(source);
        if (UpdateSource.GITHUB.equals(normalized)) {
            addGithub(routes, githubUrl, githubProxy);
            addOci(routes, artifact, ociEndpoint);
        } else {
            addOci(routes, artifact, ociEndpoint);
            addGithub(routes, githubUrl, githubProxy);
        }
        return routes;
    }

    /** 直连失败时依次尝试的公共加速线路，避免 413/超时后无法继续更新。 */
    private static final String[] FALLBACK_PROXIES = {"ghfast", "gh_acmsz", "github_chenc", "gh_monlor"};

    private static void addGithub(List<UpdateTarget> routes, String githubUrl, GithubProxy.Config proxy) {
        if (githubUrl == null || githubUrl.trim().isEmpty()) return;
        try {
            routes.add(UpdateTarget.github(proxy.rewrite(githubUrl)));
        } catch (Exception ignored) {
        }
        addCnbArchive(routes, githubUrl);
        for (String id : FALLBACK_PROXIES) {
            if (id.equals(proxy.id)) continue;
            try {
                routes.add(UpdateTarget.github(GithubProxy.resolve(id, "", "").rewrite(githubUrl)));
            } catch (Exception ignored) {
            }
        }
    }

    /** CNB 的 raw 直链对超过 100MB 的文件固定返回 413，改用同仓库 zip 归档下载后解包 APK。 */
    private static void addCnbArchive(List<UpdateTarget> routes, String githubUrl) {
        int index = githubUrl.indexOf("/-/git/raw/");
        if (index <= 0) return;
        String rest = githubUrl.substring(index + "/-/git/raw/".length());
        int slash = rest.indexOf('/');
        if (slash <= 0 || slash == rest.length() - 1) return;
        String ref = rest.substring(0, slash);
        String entry = rest.substring(rest.lastIndexOf('/') + 1);
        try {
            routes.add(UpdateTarget.archive(githubUrl.substring(0, index) + "/-/git/archive/" + ref + ".zip", entry));
        } catch (Exception ignored) {
        }
    }

    /** 自定义更新地址：直链之外再补一条 CNB 仓库 zip 归档兜底（raw 直链 >100MB 固定 413）。 */
    public static List<UpdateTarget> planForApk(String apkUrl) {
        List<UpdateTarget> routes = new ArrayList<>();
        if (apkUrl == null || apkUrl.trim().isEmpty()) return routes;
        try {
            routes.add(UpdateTarget.github(apkUrl));
        } catch (Exception ignored) {
        }
        addCnbArchive(routes, apkUrl);
        return routes;
    }

    private static void addOci(List<UpdateTarget> routes, OciArtifact artifact, String endpoint) {
        if (artifact == null || !artifact.isValid() || endpoint == null || endpoint.trim().isEmpty()) return;
        try {
            routes.add(UpdateTarget.oci(endpoint, artifact));
        } catch (Exception ignored) {
        }
    }
}
