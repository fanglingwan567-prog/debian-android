package app.birch.debian;

import android.webkit.JavascriptInterface;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Exercises the packaged console and WebView bridge without su, an Activity or a real guest. */
public final class ConsoleSmoke {
    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
    private static final class Child extends Process {
        final CountDownLatch flushed;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream() {
            @Override public void flush() { flushed.countDown(); }
        };
        final InputStream output;
        final int code;
        final AtomicInteger destroyed = new AtomicInteger();
        Child(byte[] bytes, int writes, int code) {
            output = new ByteArrayInputStream(bytes);
            flushed = new CountDownLatch(writes);
            this.code = code;
        }
        public OutputStream getOutputStream() { return sent; }
        public InputStream getInputStream() { return output; }
        public InputStream getErrorStream() { return new ByteArrayInputStream(new byte[0]); }
        public int waitFor() { return code; }
        public int exitValue() { return code; }
        public void destroy() { destroyed.incrementAndGet(); }
    }
    private static final class Display implements Console.Display {
        final BlockingQueue<String> output = new ArrayBlockingQueue<>(4);
        final CountDownLatch done = new CountDownLatch(1);
        String reason;
        public void output(Console source, String base64) { output.add(base64); }
        public void ended(Console source, String reason) { this.reason = reason; done.countDown(); }
    }
    private static byte[] frame(DataInputStream input, char expected) throws IOException {
        check(input.readUnsignedByte() == expected, "frame opcode");
        byte[] bytes = new byte[input.readUnsignedShort()];
        input.readFully(bytes);
        return bytes;
    }
    public static void main(String[] args) throws Exception {
        String[] signature = {"ready:void:int:int", "showKeyboard:void", "isKeyboardVisible:boolean", "touchSlopDp:float",
                "copyText:void:String", "fontSize:int", "saveFontSize:void:int", "input:void:String", "resize:void:int:int",
                "modifiersChanged:void:boolean:boolean", "consumed:void"};
        Set<String> actual = new HashSet<>();
        for (Method method : Class.forName("app.birch.debian.TerminalHostActivity$Bridge").getDeclaredMethods()) {
            if (!method.isAnnotationPresent(JavascriptInterface.class)) continue;
            StringBuilder key = new StringBuilder(method.getName()).append(':').append(method.getReturnType().getSimpleName());
            for (Class<?> type : method.getParameterTypes()) key.append(':').append(type.getSimpleName());
            actual.add(key.toString());
        }
        check(actual.equals(new HashSet<>(Arrays.asList(signature))), "exact JS bridge: " + actual);

        byte[] output = new byte[8193];
        Arrays.fill(output, (byte) 65);
        Child child = new Child(output, 2, 7);
        Display display = new Display();
        Console session = new Console(child, display);
        session.start();
        try {
            session.resize(0, 8);
            session.resize(2049, 8);
            session.input(new String(new char[65537]));
            session.resize(257, 2048);
            String text = new String(new char[8191]).replace('\0', 'a') + "终";
            session.input(text);
            check(child.flushed.await(5, TimeUnit.SECONDS), "writer flushed");
            DataInputStream pipe = new DataInputStream(new ByteArrayInputStream(child.sent.toByteArray()));
            check(Arrays.equals(frame(pipe, 'W'), new byte[]{1, 1, 8, 0}), "big-endian window size");
            byte[] first = frame(pipe, 'I');
            byte[] last = frame(pipe, 'I');
            ByteArrayOutputStream joined = new ByteArrayOutputStream();
            joined.write(first); joined.write(last);
            check(first.length == 8192 && pipe.read() == -1 && Arrays.equals(joined.toByteArray(), text.getBytes(StandardCharsets.UTF_8)), "UTF-8 split across frames");
            byte[] chunk = Base64.getDecoder().decode(display.output.poll(5, TimeUnit.SECONDS));
            check(Arrays.equals(chunk, Arrays.copyOf(output, 8192)), "first display chunk");
            session.consumed();
            check("QQ==".equals(display.output.poll(5, TimeUnit.SECONDS)), "last display chunk");
            session.consumed();
            check(display.done.await(5, TimeUnit.SECONDS) && "启动器退出码：7，输出已保留。".equals(display.reason), "exit reason");
        } finally { session.close(); }
        check(child.destroyed.get() == 1, "single process owner");
        Child waiting = new Child(new byte[]{1}, 0, 0);
        Display pending = new Display();
        Console cancelled = new Console(waiting, pending);
        cancelled.start();
        check(pending.output.poll(5, TimeUnit.SECONDS) != null, "output awaiting ack");
        cancelled.close();
        cancelled.close();
        check(pending.done.await(5, TimeUnit.SECONDS) && waiting.destroyed.get() == 1, "close unblocks pending ack");
        System.out.println("Packaged JS bridge, PTY frames, display acknowledgement, exit and cancellation: PASS");
    }
}
