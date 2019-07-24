/* Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 * SPDX-License-Identifier: Apache-2.0 */

package com.aws.iot.gg2k;

import java.util.concurrent.*;
import org.junit.*;
import static org.junit.Assert.*;


public class GG2KTest {
    @Test
    public void testSomeMethod() {
        try {
            CountDownLatch OK = new CountDownLatch(5);
            String tdir = System.getProperty("user.home")+"/gg2ktest";
            System.out.println("tdir = "+tdir);
            GG2K gg = new GG2K();
            gg.setWatcher(e->{
                if(e.args.length>=2) {
                    String a0 = String.valueOf(e.args[0]);
                    String a1 = String.valueOf(e.args[1]);
                    if("stdout".equals(a0))
                        if(a1.contains("RUNNING")) {
                            OK.countDown();
                            System.out.println("Victory!");
                        }
                }
            });
            gg.parseArgs("-r", tdir,
                    "-log", "stdout",
                    "-i", GG2K.class.getResource("config.yaml").toString()
            );
            gg.launch();
            System.out.println("Done");
            if(OK.await(50, TimeUnit.SECONDS))
                System.out.println("Running correctly");
            else fail("Test config didn't boot");
        } catch (Throwable ex) {
            ex.printStackTrace(System.out);
            fail();
        }
    }
    
}
