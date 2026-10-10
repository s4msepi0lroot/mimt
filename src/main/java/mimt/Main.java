package mimt;

import mimt.util.I18n;

import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class Main {
    private static final Set<String> COMMANDS = Set.of("list", "export", "export-all", "textures", "versions", "help", "--help", "-h");

    public static void main(String[] args) {
        boolean gui = !GraphicsEnvironment.isHeadless() && !Boolean.getBoolean("mimt.cli");
        List<String> rest = new ArrayList<>();
        I18n.Lang lang = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--lang") && i + 1 < args.length) lang = I18n.parse(args[++i]);
            else if (args[i].startsWith("--lang=")) lang = I18n.parse(args[i].substring(7));
            else rest.add(args[i]);
        }
        if (gui && (rest.isEmpty() || !COMMANDS.contains(rest.get(0)))) {
            List<Path> files = new ArrayList<>();
            boolean allFiles = true;
            for (String a : rest) {
                Path p = Paths.get(a);
                if (Files.exists(p)) files.add(p);
                else allFiles = false;
            }
            if (allFiles) {
                if (lang != null) I18n.save(lang);
                mimt.gui.MainFrame.launch(files);
                return;
            }
        }
        int code = mimt.cli.Cli.run(args);
        System.out.flush();
        System.exit(code);
    }
}
