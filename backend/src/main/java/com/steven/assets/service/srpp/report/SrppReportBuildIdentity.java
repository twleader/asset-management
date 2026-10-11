package com.steven.assets.service.srpp.report;

import org.springframework.stereotype.Component;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.HexFormat;

/** Compute the deployed artifact identity once at startup; report GETs perform no artifact I/O. */
@Component
public final class SrppReportBuildIdentity {
    private final String value;
    public SrppReportBuildIdentity() {
        try {
            URL location=SrppReportBuildIdentity.class.getProtectionDomain().getCodeSource().getLocation();
            value="sha256:"+digestArtifact(artifactPath(location));
        }catch(Exception failure){throw new IllegalStateException("SRPP deployed artifact identity unavailable",failure);}
    }
    public String value(){return value;}
    static Path artifactPath(URL location)throws IOException,URISyntaxException {
        if("file".equals(location.getProtocol()))return Path.of(location.toURI());
        // Spring Boot's nested-jar code source points inside the executable artifact.
        String path=location.toExternalForm().replaceFirst("^jar:(nested:|file:)","");int boundary=path.indexOf("/!");
        if(boundary<0)boundary=path.indexOf("!");if(boundary<0)throw new IOException("Unsupported artifact location");
        return Path.of(URI.create("file:"+path.substring(0,boundary)));
    }
    static String digestArtifact(Path artifact)throws IOException,NoSuchAlgorithmException {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        if(Files.isDirectory(artifact)) {
            try(var paths=Files.walk(artifact)) {
                for(Path file:paths.filter(Files::isRegularFile).sorted().toList()) {
                    String relative=artifact.relativize(file).toString().replace(File.separatorChar,'/');
                    digest.update(relative.getBytes(StandardCharsets.UTF_8));digest.update((byte)0);feed(digest,file);digest.update((byte)0);
                }
            }
        }else feed(digest,artifact);
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void feed(MessageDigest digest,Path file)throws IOException {
        try(InputStream in=Files.newInputStream(file)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);}
    }
}
