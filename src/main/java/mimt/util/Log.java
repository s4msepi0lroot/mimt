package mimt.util;

import java.util.function.Consumer;

public final class Log {
    private static volatile Consumer<String> sink = System.err::println;
    public static volatile boolean verbose = false;

    public static void setSink(Consumer<String> s) { sink = s; }

    public static void info(String s) { sink.accept(s); }

    public static void warn(String s) { sink.accept("[!] " + s); }

    public static void debug(String s) { if (verbose) sink.accept("[debug] " + s); }
}
