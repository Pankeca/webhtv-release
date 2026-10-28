package com.fongmi.android.tv.update;

public final class UpdateTarget {

    public enum Kind {
        GITHUB,
        OCI,
        /** CNB 等仓库的 zip 归档：需要先整包下载再解出 APK。 */
        ARCHIVE
    }

    public final Kind kind;
    public final String url;
    public final String endpoint;
    public final String entry;
    public final OciArtifact artifact;

    private UpdateTarget(Kind kind, String url, String endpoint, String entry, OciArtifact artifact) {
        this.kind = kind;
        this.url = url;
        this.endpoint = endpoint;
        this.entry = entry;
        this.artifact = artifact;
    }

    public static UpdateTarget github(String url) {
        return new UpdateTarget(Kind.GITHUB, UpdateUrl.requireHttpsUrl(url), "", "", null);
    }

    public static UpdateTarget archive(String url, String entry) {
        if (entry == null || entry.trim().isEmpty()) throw new IllegalArgumentException("Entry required");
        return new UpdateTarget(Kind.ARCHIVE, UpdateUrl.requireHttpsUrl(url), "", entry.trim(), null);
    }

    public static UpdateTarget oci(String endpoint, OciArtifact artifact) {
        if (artifact == null || !artifact.isValid()) throw new IllegalArgumentException("Invalid OCI artifact");
        return new UpdateTarget(Kind.OCI, "", UpdateUrl.requireHttpsOrigin(endpoint), "", artifact);
    }
}
