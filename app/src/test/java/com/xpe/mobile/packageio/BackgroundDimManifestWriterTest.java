package com.xpe.mobile.packageio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class BackgroundDimManifestWriterTest {
    @Test public void unchangedScalarAndUnknownLinesRemainByteExact() {
        String source = "\ufeff# comment\r\nbackgroundDim: '0.10' # keep\r\nfuture: {x: 1}\r\n";
        assertEquals(source, BackgroundDimManifestWriter.update(source, .1));
        assertEquals(source.replace("'0.10' # keep", "0.8 # keep"),
                BackgroundDimManifestWriter.update(source, .8));
    }

    @Test public void appendsOnlyWhenNonDefaultAndPreservesNewlineStyleAndBom() {
        String source = "\ufeffname: \"Test\"\r\nfuture: keep";
        assertEquals(source, BackgroundDimManifestWriter.update(source, .6));
        assertEquals(source + "\r\nbackgroundDim: 0.4\r\n",
                BackgroundDimManifestWriter.update(source, .4));
        assertEquals("backgroundDim: 1.0\n", BackgroundDimManifestWriter.update("", 1));
        assertEquals("\ufeffbackgroundDim: 0.5\n",
                BackgroundDimManifestWriter.update("\ufeffbackgroundDim: 0.2\n", .5));
    }

    @Test public void nestedDimInIntroDoesNotOverrideOrRewritePackageDim() {
        String source = "name: Test\nintro: |-\n  backgroundDim: 0.2\n";
        assertEquals(source + "backgroundDim: 0.4\n", BackgroundDimManifestWriter.update(source, .4));
        String indented = "  name: Test\n  intro: |-\n    backgroundDim: 0.2\n";
        assertEquals(indented + "  backgroundDim: 0.4\n", BackgroundDimManifestWriter.update(indented, .4));
    }
}
