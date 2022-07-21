package com.marlan.weatherupdate.utilities;

import lombok.experimental.UtilityClass;

import static java.lang.System.getProperty;
import static java.lang.System.out;

@UtilityClass
public class DirHandler {
    public static String getWorkingDir(String[] args) {
        if (args.length != 0) {
            System.setProperty("user.dir", args[0]);
        }
        out.println("INFO: Working Directory: " + getProperty("user.dir") + "\\");
        return getProperty("user.dir") + "\\";
    }
}
