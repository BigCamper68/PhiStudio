package com.xpe.mobile.preview;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.junit.Test;
import static org.junit.Assert.*;

public class PrprBezierOracleTest {
    @Test public void matchesUpstreamRustIncludingPrerollAndFlatTangents() throws Exception {
        int count=0;
        try(var reader=new BufferedReader(new InputStreamReader(
                getClass().getResourceAsStream("/prpr/bezier-oracle.csv"),StandardCharsets.UTF_8))) {
            reader.readLine();String row;
            while((row=reader.readLine())!=null) {
                String[] c=row.split(",");double[] p=new double[4];
                for(int i=0;i<4;i++)p[i]=Double.parseDouble(c[i]);
                float x=Float.parseFloat(c[4]),expected=Float.parseFloat(c[5]);
                assertEquals(row,expected,PrprEvents.progress(x,1,.2,.8,true,p),
                        1e-6+Math.abs(expected)*2e-7);
                float integral=Float.parseFloat(c[6]);
                assertEquals(row,integral,PrprBezier.integral(x,p),1e-6+Math.abs(integral)*2e-7);
                count++;
            }
        }
        assertEquals(112,count);
    }
}
