/*
 * Copyright (c) 2014, 2015, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package jdk.tools.jimage;

import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import static java.nio.file.StandardOpenOption.READ;
import static java.nio.file.StandardOpenOption.WRITE;
import java.util.LinkedList;
import java.util.List;
import jdk.internal.jimage.BasicImageReader;
import jdk.internal.jimage.ImageHeader;
import static jdk.internal.jimage.ImageHeader.MAGIC;
import static jdk.internal.jimage.ImageHeader.MAJOR_VERSION;
import static jdk.internal.jimage.ImageHeader.MINOR_VERSION;
import jdk.internal.jimage.ImageLocation;
import jdk.tools.jlink.internal.ImageResourcesTree;
import jdk.tools.jlink.internal.TaskHelper;
import jdk.tools.jlink.internal.TaskHelper.BadArgs;
import static jdk.tools.jlink.internal.TaskHelper.JIMAGE_BUNDLE;
import jdk.tools.jlink.internal.TaskHelper.Option;
import jdk.tools.jlink.internal.TaskHelper.OptionsHelper;

class JImageTask {

    static final Option<?>[] recognizedOptions = {
        new Option<JImageTask>(true, (task, opt, arg) -> {
            task.options.directory = arg;
        }, "--dir"),
        new Option<JImageTask>(false, (task, opt, arg) -> {
            task.options.fullVersion = true;
        }, true, "--fullversion"),
        new Option<JImageTask>(false, (task, opt, arg) -> {
            task.options.help = true;
        }, "--help"),
        new Option<JImageTask>(true, (task, opt, arg) -> {
            task.options.flags = arg;
        }, "--flags"),
        new Option<JImageTask>(false, (task, opt, arg) -> {
            task.options.verbose = true;
        }, "--verbose"),
        new Option<JImageTask>(false, (task, opt, arg) -> {
            task.options.version = true;
        }, "--version")
    };
    private static final TaskHelper taskHelper
            = new TaskHelper(JIMAGE_BUNDLE);
    private static final OptionsHelper<JImageTask> optionsHelper
            = taskHelper.newOptionsHelper(JImageTask.class, recognizedOptions);

    static class OptionsValues {
        Task task = Task.LIST;
        String directory = ".";
        boolean fullVersion;
        boolean help;
        String flags;
        boolean verbose;
        boolean version;
        List<File> jimages = new LinkedList<>();
    }

    private static final String PROGNAME = "jimage";
    private final OptionsValues options = new OptionsValues();

    enum Task {
        EXTRACT,
        INFO,
        LIST,
        SET,
        VERIFY
    };

    private String pad(String string, int width, boolean justifyRight) {
        int length = string.length();

        if (length == width) {
            return string;
        }

        if (length > width) {
            return string.substring(0, width);
        }

        int padding = width - length;

        StringBuilder sb = new StringBuilder(width);
        if (justifyRight) {
            for (int i = 0; i < padding; i++) {
                sb.append(' ');
            }
        }

        sb.append(string);

        if (!justifyRight) {
            for (int i = 0; i < padding; i++) {
                sb.append(' ');
            }
        }

        return sb.toString();
    }

    private String pad(String string, int width) {
        return pad(string, width, false);
    }

    private String pad(long value, int width) {
        return pad(Long.toString(value), width, true);
    }

    private static final int EXIT_OK = 0;        // No errors.
    private static final int EXIT_ERROR = 1;     // Completed but reported errors.
    private static final int EXIT_CMDERR = 2;    // Bad command-line arguments and/or switches.
    private static final int EXIT_SYSERR = 3;    // System error or resource exhaustion.
    private static final int EXIT_ABNORMAL = 4;  // Terminated abnormally.

    int run(String[] args) {
        if (log == null) {
            setLog(new PrintWriter(System.out));
        }

        if (args.length == 0) {
            log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            return EXIT_ABNORMAL;
        }

        try {
            List<String> unhandled = optionsHelper.handleOptions(this, args);
            if(!unhandled.isEmpty()) {
                try {
                    options.task = Enum.valueOf(Task.class, unhandled.get(0).toUpperCase());
                } catch (IllegalArgumentException ex) {
                    throw taskHelper.newBadArgs("err.not.a.task", unhandled.get(0));
                }
                for(int i = 1; i < unhandled.size(); i++) {
                    options.jimages.add(new File(unhandled.get(i)));
                }
            } else {
                throw taskHelper.newBadArgs("err.not.a.task", "<unspecified>");
            }
            if (options.help) {
                optionsHelper.showHelp(PROGNAME);
            }
            if (options.version || options.fullVersion) {
                taskHelper.showVersion(options.fullVersion);
            }
            boolean ok = run();
            return ok ? EXIT_OK : EXIT_ERROR;
        } catch (BadArgs e) {
            taskHelper.reportError(e.key, e.args);
            if (e.showUsage) {
                log.println(taskHelper.getMessage("main.usage.summary", PROGNAME));
            }
            return EXIT_CMDERR;
        } catch (Exception x) {
            x.printStackTrace();
            return EXIT_ABNORMAL;
        } finally {
            log.flush();
        }
    }

    private void listTitle(File file, BasicImageReader reader) {
        log.println("jimage: " + file);
    }

    private interface JImageAction {
        public void apply(File file, BasicImageReader reader) throws IOException, BadArgs;
    }

    private interface ModuleAction {
         public void apply(BasicImageReader reader,
                 String oldModule, String newModule) throws IOException, BadArgs;
    }

    private interface ResourceAction {
        public void apply(BasicImageReader reader, String name,
                ImageLocation location) throws IOException, BadArgs;
    }

    private void extract(BasicImageReader reader, String name,
            ImageLocation location) throws IOException, BadArgs {
        File directory = new File(options.directory);
        byte[] bytes = reader.getResource(location);
        File resource =  new File(directory, name);
        File parent = resource.getParentFile();

        if (parent.exists()) {
            if (!parent.isDirectory()) {
                throw taskHelper.newBadArgs("err.cannot.create.dir", parent.getAbsolutePath());
            }
        } else if (!parent.mkdirs()) {
            throw taskHelper.newBadArgs("err.cannot.create.dir", parent.getAbsolutePath());
        }

        if (!ImageResourcesTree.isTreeInfoResource(name)) {
            Files.write(resource.toPath(), bytes);
        }
    }

    private static final int OFFSET_WIDTH = 12;
    private static final int SIZE_WIDTH = 10;
    private static final int COMPRESSEDSIZE_WIDTH = 10;

    private String trimModule(String name) {
        int offset = name.indexOf('/', 1);

        if (offset != -1 && offset + 1 < name.length()) {
            return name.substring(offset + 1);
        }

        return name;
    }

    private void print(String name, ImageLocation location) {
        log.print(pad(location.getContentOffset(), OFFSET_WIDTH) + " ");
        log.print(pad(location.getUncompressedSize(), SIZE_WIDTH) + " ");
        log.print(pad(location.getCompressedSize(), COMPRESSEDSIZE_WIDTH) + " ");
        log.println(trimModule(name));
    }

    private void print(BasicImageReader reader, String name) {
        if (options.verbose) {
            print(name, reader.findLocation(name));
        } else {
            log.println("    " + trimModule(name));
        }
    }

    private void info(File file, BasicImageReader reader) throws IOException {
        ImageHeader header = reader.getHeader();

        log.println(" Major Version:  " + header.getMajorVersion());
        log.println(" Minor Version:  " + header.getMinorVersion());
        log.println(" Flags:          " + Integer.toHexString(header.getMinorVersion()));
        log.println(" Resource Count: " + header.getResourceCount());
        log.println(" Table Length:   " + header.getTableLength());
        log.println(" Offsets Size:   " + header.getOffsetsSize());
        log.println(" Redirects Size: " + header.getRedirectSize());
        log.println(" Locations Size: " + header.getLocationsSize());
        log.println(" Strings Size:   " + header.getStringsSize());
        log.println(" Index Size:     " + header.getIndexSize());
    }

    private void listModule(BasicImageReader reader, String oldModule, String newModule) {
        log.println();
        log.println("Module: " + newModule);

        if (options.verbose) {
            log.print(pad("Offset", OFFSET_WIDTH) + " ");
            log.print(pad("Size", SIZE_WIDTH) + " ");
            log.print(pad("Compressed", COMPRESSEDSIZE_WIDTH) + " ");
            log.println("Entry");
        }
    }

    private void list(BasicImageReader reader, String name, ImageLocation location) {
        print(reader, name);
    }

    void set(File file, BasicImageReader reader) throws BadArgs {
        try {
            ImageHeader oldHeader = reader.getHeader();

            int value = 0;
            try {
                value = Integer.valueOf(options.flags);
            } catch (NumberFormatException ex) {
                throw taskHelper.newBadArgs("err.flags.not.int", options.flags);
            }

            ImageHeader newHeader = new ImageHeader(MAGIC, MAJOR_VERSION, MINOR_VERSION,
                    value,
                    oldHeader.getResourceCount(), oldHeader.getTableLength(),
                    oldHeader.getLocationsSize(), oldHeader.getStringsSize());

            ByteBuffer buffer = ByteBuffer.allocate(ImageHeader.getHeaderSize());
            buffer.order(ByteOrder.nativeOrder());
            newHeader.writeTo(buffer);
            buffer.rewind();

            try (FileChannel channel = FileChannel.open(file.toPath(), READ, WRITE)) {
                channel.write(buffer, 0);
            }
        } catch (IOException ex) {
            throw taskHelper.newBadArgs("err.cannot.update.file", file.getName());
        }
    }

     void verify(BasicImageReader reader, String name, ImageLocation location) {
        if (name.endsWith(".class")) {
            byte[] bytes = reader.getResource(location);

            if (bytes == null || bytes.length <= 4 ||
                (bytes[0] & 0xFF) != 0xCA ||
                (bytes[1] & 0xFF) != 0xFE ||
                (bytes[2] & 0xFF) != 0xBA ||
                (bytes[3] & 0xFF) != 0xBE) {
                log.print(" NOT A CLASS: ");
                print(reader, name);
            }
        }
    }

    private void iterate(JImageAction jimageAction,
            ModuleAction moduleAction,
            ResourceAction resourceAction) throws IOException, BadArgs {
        if (options.jimages.isEmpty()) {
            throw taskHelper.newBadArgs("err.no.jimage");
        }

        for (File file : options.jimages) {
            if (!file.exists() || !file.isFile()) {
                throw taskHelper.newBadArgs("err.not.a.jimage", file.getName());
            }

            try (BasicImageReader reader = BasicImageReader.open(file.toPath())) {
                if (jimageAction != null) {
                    jimageAction.apply(file, reader);
                }

                if (resourceAction != null) {
                    String[] entryNames = reader.getEntryNames();
                    String oldModule = "";

                    for (String name : entryNames) {
                        if (!ImageResourcesTree.isTreeInfoResource(name)) {
                            if (moduleAction != null) {
                                int offset = name.indexOf('/', 1);

                                String newModule = offset != -1 ?
                                        name.substring(1, offset) :
                                        "<unknown>";

                                if (!oldModule.equals(newModule)) {
                                    moduleAction.apply(reader, oldModule, newModule);
                                    oldModule = newModule;
                                }
                            }

                            ImageLocation location = reader.findLocation(name);
                            resourceAction.apply(reader, name, location);
                        }
                    }
                }
            }
        }
    }

    private boolean run() throws Exception, BadArgs {
        switch (options.task) {
            case EXTRACT:
                iterate(null, null, this::extract);
                break;
            case INFO:
                iterate(this::info, null, null);
                break;
            case LIST:
                iterate(this::listTitle, this::listModule, this::list);
                break;
            case SET:
                iterate(this::set, null, null);
                break;
            case VERIFY:
                iterate(this::listTitle, null, this::verify);
                break;
            default:
                throw taskHelper.newBadArgs("err.invalid.task", options.task.name()).showUsage(true);
        }
        return true;
    }

    private PrintWriter log;
    void setLog(PrintWriter out) {
        log = out;
        taskHelper.setLog(log);
    }
}
