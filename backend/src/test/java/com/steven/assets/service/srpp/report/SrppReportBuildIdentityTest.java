package com.steven.assets.service.srpp.report;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import static org.assertj.core.api.Assertions.assertThat;

class SrppReportBuildIdentityTest {
    @TempDir Path temp;
    @Test void loadedArtifactHasStableCryptographicIdentityComputedAtConstruction(){
        var first=new SrppReportBuildIdentity();assertThat(first.value()).matches("sha256:[0-9a-f]{64}");assertThat(first.value()).isEqualTo(new SrppReportBuildIdentity().value());
    }
    @Test void classAndResourceByteChangesAlterExplodedArtifactIdentity()throws Exception{
        Files.writeString(temp.resolve("report.class"),"v1");Files.writeString(temp.resolve("model.json"),"original");String first=SrppReportBuildIdentity.digestArtifact(temp);
        Files.writeString(temp.resolve("model.json"),"revised");assertThat(SrppReportBuildIdentity.digestArtifact(temp)).isNotEqualTo(first);Files.writeString(temp.resolve("model.json"),"original");assertThat(SrppReportBuildIdentity.digestArtifact(temp)).isEqualTo(first);
        Files.writeString(temp.resolve("report.class"),"v2");assertThat(SrppReportBuildIdentity.digestArtifact(temp)).isNotEqualTo(first);
    }
    @Test void springBootNestedLocationHashesOuterDeployedJar()throws Exception{
        URL nested=new URL(null,"jar:nested:/app/business.jar/!BOOT-INF/classes/!",new URLStreamHandler(){protected URLConnection openConnection(URL u){throw new UnsupportedOperationException();}});
        assertThat(SrppReportBuildIdentity.artifactPath(nested)).isEqualTo(Path.of("/app/business.jar"));
    }
}
