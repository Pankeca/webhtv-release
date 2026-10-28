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
        for (String id : FALLBACK_PROXIES) {
            if (id.equals(proxy.id)) continue;
            try {
                routes.add(UpdateTarget.github(GithubProxy.resolve(id, "", "").rewrite(githubUrl)));
            } catch (Exception ignored) {
            }
        }
    }

    private static void addOci(List<UpdateTarget> routes, OciArtifact artifact, String endpoint) {
        if (artifact == null || !artifact.isValid() || endpoint == null || endpoint.trim().isEmpty()) return;
        try {
            routes.add(UpdateTarget.oci(endpoint, artifact));
        } catch (Exception ignored) {
        }
    }
}
