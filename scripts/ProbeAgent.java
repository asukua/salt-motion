import java.lang.instrument.Instrumentation;
public final class ProbeAgent {
    public static volatile Instrumentation instrumentation;
    public static void premain(String args,Instrumentation value){instrumentation=value;}
}
