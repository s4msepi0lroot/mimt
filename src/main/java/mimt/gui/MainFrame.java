package mimt.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatLightLaf;
import mimt.mi.MiModel;
import mimt.mi.MiWriter;
import mimt.scan.Entry;
import mimt.scan.TextureGuess;
import mimt.scan.Workspace;
import mimt.src.Source;
import mimt.util.Http;
import mimt.util.I18n;
import mimt.util.Img;
import mimt.util.Log;
import mimt.vanilla.VanillaMeta;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.prefs.Preferences;

import static mimt.util.I18n.t;

public final class MainFrame extends JFrame {
    public enum Theme { DARK, LIGHT, SYSTEM }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread th = new Thread(r, "mimt-worker");
        th.setDaemon(true);
        return th;
    });
    private static final AtomicInteger GENERATION = new AtomicInteger();
    private static List<String> versions;

    private final String auto = t("Auto"), pick = t("Choose file…"), fromModel = t("From model");
    private final Workspace ws;
    private final Preferences prefs = I18n.prefs();

    private final DefaultListModel<Source> srcModel = new DefaultListModel<>();
    private final JList<Source> srcList = new JList<>(srcModel);
    private final JTextField search = new JTextField();
    private final DefaultTreeModel treeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
    private final JTree tree = new JTree(treeModel);
    private final PreviewPanel preview = new PreviewPanel();
    private final JComboBox<Object> texCombo = new JComboBox<>();
    private final JTextArea info = new JTextArea(7, 30);
    private final JTextArea log = new JTextArea(6, 80);
    private final JComboBox<String> verCombo = new JComboBox<>(new String[]{auto});
    private final JCheckBox autoDl = new JCheckBox(t("Download Minecraft files"), true);
    private final JCheckBox tintBox = new JCheckBox(t("Tint (grass, foliage)"), true);
    private final JLabel status = new JLabel(" ");
    private final JProgressBar progress = new JProgressBar();
    private final JButton exportSel = new JButton(t("Export selected…"));
    private final JButton exportAll = new JButton(t("Export all (filtered)…"));
    private final javax.swing.Timer searchTimer;

    private Entry currentEntry;
    private BufferedImage texOverride;
    private boolean texComboUpdating;
    private Object lastTexItem;

    public static void launch(List<Path> files) {
        I18n.set(I18n.saved());
        SwingUtilities.invokeLater(() -> {
            applyTheme(savedTheme(), null);
            MainFrame f = new MainFrame(new Workspace(), null);
            f.setVisible(true);
            if (!files.isEmpty()) f.addPaths(files);
        });
    }

    static Theme savedTheme() {
        try { return Theme.valueOf(I18n.prefs().get("theme", "DARK")); } catch (Exception e) { return Theme.DARK; }
    }

    static void applyTheme(Theme th, Window w) {
        try {
            switch (th) {
                case LIGHT -> UIManager.setLookAndFeel(new FlatLightLaf());
                case SYSTEM -> UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                default -> UIManager.setLookAndFeel(new FlatDarkLaf());
            }
        } catch (Exception e) {
            Log.warn("Look and feel: " + e.getMessage());
        }
        if (w != null) {
            if (UIManager.getLookAndFeel() instanceof FlatLaf) FlatLaf.updateUI();
            else for (Window x : Window.getWindows()) SwingUtilities.updateComponentTreeUI(x);
        }
    }

    private MainFrame(Workspace ws, MainFrame old) {
        super(t("mimodel-tool — Minecraft models → Mine-imator (.mimodel)"));
        this.ws = ws;
        lastTexItem = auto;
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        Log.setSink(s -> {
            System.out.println(s);
            SwingUtilities.invokeLater(() -> {
                log.append(s + "\n");
                log.setCaretPosition(log.getDocument().getLength());
            });
        });

        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        JButton addFiles = new JButton(t("Add files…"));
        addFiles.setToolTipText(t("Mod (.jar), Minecraft client (.jar), resource pack (.zip), .bbmodel, .geo.json, .jem"));
        JButton addDir = new JButton(t("Add folder…"));
        addDir.setToolTipText(t("Unpacked resource pack or a folder with mods (.minecraft/mods)"));
        JButton remove = new JButton(t("Remove"));
        tb.add(addFiles);
        tb.add(addDir);
        tb.add(remove);
        tb.addSeparator();
        tb.add(new JLabel(" " + t("MC version:") + " "));
        verCombo.setMaximumSize(new Dimension(110, 28));
        verCombo.setToolTipText(t("Minecraft version for missing vanilla files (Auto — from the mod / resource pack)"));
        tb.add(verCombo);
        tb.addSeparator();
        tb.add(autoDl);
        tb.add(tintBox);
        tb.add(Box.createHorizontalGlue());
        tb.add(exportSel);
        tb.add(exportAll);

        srcList.setCellRenderer(new DefaultListCellRenderer() {
            @Override public Component getListCellRendererComponent(JList<?> l, Object v, int i, boolean s, boolean f) {
                super.getListCellRendererComponent(l, v, i, s, f);
                Source src = (Source) v;
                setText(src.displayName + "  [" + Workspace.kindName(src.kind) + (src.mcVersion != null ? ", " + src.mcVersion : "") + "]");
                setToolTipText(src.path.toString());
                return this;
            }
        });
        JScrollPane srcScroll = new JScrollPane(srcList);
        srcScroll.setBorder(BorderFactory.createTitledBorder(t("Sources (you can drop files here)")));
        srcScroll.setPreferredSize(new Dimension(320, 130));

        tree.setRootVisible(false);
        tree.setShowsRootHandles(true);
        tree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override public Component getTreeCellRendererComponent(JTree tr, Object v, boolean s, boolean e, boolean leaf, int r, boolean f) {
                super.getTreeCellRendererComponent(tr, v, s, e, leaf, r, f);
                Object u = ((DefaultMutableTreeNode) v).getUserObject();
                setToolTipText(u instanceof Entry en ? en.fullId() + "  —  " + en.source.displayName : null);
                return this;
            }
        });
        ToolTipManager.sharedInstance().registerComponent(tree);
        search.putClientProperty("JTextField.placeholderText", t("name, id or folder"));
        search.putClientProperty("JTextField.showClearButton", true);
        JPanel searchRow = new JPanel(new BorderLayout(4, 0));
        searchRow.add(new JLabel(t("Search:") + " "), BorderLayout.WEST);
        searchRow.add(search, BorderLayout.CENTER);
        JPanel treePanel = new JPanel(new BorderLayout(0, 4));
        treePanel.add(searchRow, BorderLayout.NORTH);
        treePanel.add(new JScrollPane(tree), BorderLayout.CENTER);
        treePanel.setBorder(BorderFactory.createTitledBorder(t("Models")));
        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, srcScroll, treePanel);
        left.setResizeWeight(0.2);

        JPanel texRow = new JPanel(new BorderLayout(4, 0));
        texRow.add(new JLabel(t("Texture:") + " "), BorderLayout.WEST);
        texRow.add(texCombo, BorderLayout.CENTER);
        JButton resetView = new JButton(t("Reset view"));
        texRow.add(resetView, BorderLayout.EAST);
        texCombo.setEnabled(false);
        info.setEditable(false);
        info.setLineWrap(true);
        info.setWrapStyleWord(true);
        JPanel bottomRight = new JPanel(new BorderLayout(0, 4));
        bottomRight.add(texRow, BorderLayout.NORTH);
        bottomRight.add(new JScrollPane(info), BorderLayout.CENTER);
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, preview, bottomRight);
        right.setResizeWeight(0.75);

        JSplitPane main = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        main.setDividerLocation(340);
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setBorder(BorderFactory.createTitledBorder(t("Log")));
        JSplitPane all = new JSplitPane(JSplitPane.VERTICAL_SPLIT, main, logScroll);
        all.setResizeWeight(0.82);

        JPanel statusBar = new JPanel(new BorderLayout(6, 0));
        statusBar.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        statusBar.add(status, BorderLayout.CENTER);
        progress.setPreferredSize(new Dimension(220, 16));
        progress.setVisible(false);
        statusBar.add(progress, BorderLayout.EAST);

        setJMenuBar(buildMenu());
        getContentPane().add(tb, BorderLayout.NORTH);
        getContentPane().add(all, BorderLayout.CENTER);
        getContentPane().add(statusBar, BorderLayout.SOUTH);

        addFiles.addActionListener(e -> chooseFiles(false));
        addDir.addActionListener(e -> chooseFiles(true));
        remove.addActionListener(e -> removeSelected());
        resetView.addActionListener(e -> preview.resetView());
        searchTimer = new javax.swing.Timer(250, e -> rebuildTree());
        searchTimer.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { searchTimer.restart(); }
            public void removeUpdate(DocumentEvent e) { searchTimer.restart(); }
            public void changedUpdate(DocumentEvent e) { searchTimer.restart(); }
        });
        tree.addTreeSelectionListener(e -> onSelect());
        texCombo.addActionListener(e -> onTexture());
        verCombo.addActionListener(e -> {
            Object v = verCombo.getSelectedItem();
            ws.mcVersion = verCombo.getSelectedIndex() <= 0 ? null : (String) v;
            reloadCurrent();
        });
        autoDl.addActionListener(e -> { ws.autoVanilla = autoDl.isSelected(); prefs.putBoolean("autoDl", autoDl.isSelected()); });
        tintBox.addActionListener(e -> { ws.tint = tintBox.isSelected(); prefs.putBoolean("tint", tintBox.isSelected()); reloadCurrent(); });
        exportSel.addActionListener(e -> exportSelected());
        exportAll.addActionListener(e -> exportFiltered());
        autoDl.setSelected(prefs.getBoolean("autoDl", true));
        tintBox.setSelected(prefs.getBoolean("tint", true));
        ws.autoVanilla = autoDl.isSelected();
        ws.tint = tintBox.isSelected();

        TransferHandler dnd = new TransferHandler() {
            @Override public boolean canImport(TransferSupport s) { return s.isDataFlavorSupported(DataFlavor.javaFileListFlavor); }

            @SuppressWarnings("unchecked")
            @Override public boolean importData(TransferSupport s) {
                try {
                    List<File> files = (List<File>) s.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    addPaths(files.stream().map(File::toPath).toList());
                    return true;
                } catch (Exception ex) {
                    return false;
                }
            }
        };
        for (JComponent c : new JComponent[]{srcList, tree, preview, (JComponent) getContentPane()}) c.setTransferHandler(dnd);

        if (versions != null) for (String v : versions) verCombo.addItem(v);
        else WORKER.submit(() -> {
            List<String> vs = VanillaMeta.releases();
            SwingUtilities.invokeLater(() -> {
                versions = vs;
                for (String v : vs) verCombo.addItem(v);
            });
        });

        if (old != null) {
            setBounds(old.getBounds());
            setExtendedState(old.getExtendedState());
            log.setText(old.log.getText());
            for (int i = 0; i < old.srcModel.size(); i++) srcModel.addElement(old.srcModel.get(i));
            search.setText(old.search.getText());
            if (ws.mcVersion != null) verCombo.setSelectedItem(ws.mcVersion);
            rebuildTree();
            preview.setMessage(t("Select a model on the left"));
            if (old.currentEntry != null) selectEntry(old.currentEntry);
        } else {
            setSize(1200, 800);
            setLocationRelativeTo(null);
            preview.setMessage(t("Add a mod (.jar), the Minecraft client or a resource pack\n(the “Add files…” button or drop a file onto the window)"));
            Log.info(t("mimodel-tool is ready. Cache: ") + Http.cacheDir());
        }
        searchTimer.stop();
        updateButtons();
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();
        int ctrl = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        JMenu file = new JMenu(t("File"));
        file.add(item(t("Add files…"), KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl), () -> chooseFiles(false)));
        file.add(item(t("Add folder…"), KeyStroke.getKeyStroke(KeyEvent.VK_O, ctrl | InputEvent.SHIFT_DOWN_MASK), () -> chooseFiles(true)));
        file.add(item(t("Remove selected sources"), null, this::removeSelected));
        file.addSeparator();
        file.add(item(t("Export selected…"), KeyStroke.getKeyStroke(KeyEvent.VK_E, ctrl), this::exportSelected));
        file.add(item(t("Export all (filtered)…"), KeyStroke.getKeyStroke(KeyEvent.VK_E, ctrl | InputEvent.SHIFT_DOWN_MASK), this::exportFiltered));
        file.addSeparator();
        file.add(item(t("Exit"), null, () -> System.exit(0)));
        mb.add(file);

        JMenu view = new JMenu(t("View"));
        JMenu theme = new JMenu(t("Theme"));
        ButtonGroup tg = new ButtonGroup();
        Theme cur = savedTheme();
        for (Theme th : Theme.values()) {
            String name = switch (th) {
                case DARK -> t("Dark");
                case LIGHT -> t("Light");
                case SYSTEM -> t("System");
            };
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(name, th == cur);
            mi.addActionListener(e -> {
                prefs.put("theme", th.name());
                applyTheme(th, this);
            });
            tg.add(mi);
            theme.add(mi);
        }
        view.add(theme);
        JMenu lang = new JMenu(t("Language"));
        ButtonGroup lg = new ButtonGroup();
        for (I18n.Lang l : I18n.Lang.values()) {
            JRadioButtonMenuItem mi = new JRadioButtonMenuItem(l.title, l == I18n.get());
            mi.addActionListener(e -> switchLanguage(l));
            lg.add(mi);
            lang.add(mi);
        }
        view.add(lang);
        view.addSeparator();
        view.add(item(t("Reset view"), KeyStroke.getKeyStroke(KeyEvent.VK_R, ctrl), preview::resetView));
        mb.add(view);

        JMenu help = new JMenu(t("Help"));
        help.add(item(t("Open cache folder"), null, () -> openFolder(Http.cacheDir())));
        help.add(item(t("About"), null, () -> JOptionPane.showMessageDialog(this,
                "mimodel-tool " + version() + "\n\n" + t("Converts Minecraft models (mods, the vanilla client, resource packs)\ninto Mine-imator .mimodel files.")
                        + "\n\n" + t("Cache: ") + Http.cacheDir(), t("About"), JOptionPane.INFORMATION_MESSAGE)));
        mb.add(help);
        return mb;
    }

    private static JMenuItem item(String text, KeyStroke ks, Runnable r) {
        JMenuItem mi = new JMenuItem(text);
        if (ks != null) mi.setAccelerator(ks);
        mi.addActionListener(e -> r.run());
        return mi;
    }

    private static String version() {
        String v = MainFrame.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private void switchLanguage(I18n.Lang l) {
        if (l == I18n.get()) return;
        I18n.set(l);
        I18n.save(l);
        GENERATION.incrementAndGet();
        MainFrame f = new MainFrame(ws, this);
        f.setVisible(true);
        dispose();
    }

    private void openFolder(Path p) {
        try {
            Files.createDirectories(p);
            Desktop.getDesktop().open(p.toFile());
        } catch (Exception ex) {
            Log.warn(t("Failed to open the folder: ") + ex.getMessage());
        }
    }

    private void busy(String msg) {
        SwingUtilities.invokeLater(() -> {
            status.setText(msg);
            progress.setIndeterminate(true);
            progress.setVisible(true);
        });
    }

    private void idle(String msg) {
        SwingUtilities.invokeLater(() -> {
            status.setText(msg);
            progress.setVisible(false);
            progress.setIndeterminate(false);
            updateButtons();
        });
    }

    private void updateButtons() {
        exportSel.setEnabled(!ws.entries.isEmpty());
        exportAll.setEnabled(!ws.entries.isEmpty());
    }

    private String countText() { return t("Models: ") + ws.entries.size(); }

    private void chooseFiles(boolean dirs) {
        JFileChooser fc = new JFileChooser(prefs.get("lastIn", System.getProperty("user.home")));
        fc.setMultiSelectionEnabled(true);
        if (dirs) {
            fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            fc.setDialogTitle(t("Resource pack folder or a folder with mods"));
        } else {
            fc.setDialogTitle(t("Mods, Minecraft client, resource packs, models"));
            fc.setFileFilter(new FileNameExtensionFilter(t("Mods, resource packs, models (*.jar, *.zip, *.bbmodel, *.json, *.jem)"),
                    "jar", "zip", "mcpack", "bbmodel", "json", "jem"));
        }
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File[] sel = fc.getSelectedFiles();
        if (sel.length == 0 && fc.getSelectedFile() != null) sel = new File[]{fc.getSelectedFile()};
        if (sel.length == 0) return;
        File parent = sel[0].getParentFile();
        if (parent != null) prefs.put("lastIn", parent.getPath());
        addPaths(Arrays.stream(sel).map(File::toPath).toList());
    }

    void addPaths(List<Path> paths) {
        WORKER.submit(() -> {
            for (Path p : paths) {
                busy(t("Reading ") + p.getFileName() + "…");
                try {
                    List<Source> added = ws.addPath(p);
                    SwingUtilities.invokeLater(() -> { for (Source s : added) srcModel.addElement(s); });
                } catch (Throwable ex) {
                    Log.warn(t("Failed to open ") + p + ": " + ex);
                }
            }
            SwingUtilities.invokeLater(this::rebuildTree);
            idle(countText());
        });
    }

    private void removeSelected() {
        List<Source> sel = srcList.getSelectedValuesList();
        if (sel.isEmpty()) return;
        WORKER.submit(() -> {
            for (Source s : sel) {
                ws.removeSource(s);
                try { s.close(); } catch (Exception ignored) { }
            }
            SwingUtilities.invokeLater(() -> {
                for (Source s : sel) srcModel.removeElement(s);
                currentEntry = null;
                preview.setMessage(t("Select a model on the left"));
                info.setText("");
                rebuildTree();
            });
            idle(countText());
        });
    }

    private List<Entry> filtered() {
        String q = search.getText().trim().toLowerCase(Locale.ROOT);
        List<Entry> r = new ArrayList<>();
        for (Entry e : ws.entries) {
            if (e.source.hidden) continue;
            if (!q.isEmpty()) {
                String hay = (e.name + " " + e.fullId() + " " + e.group).toLowerCase(Locale.ROOT);
                boolean ok = true;
                for (String w : q.split("\\s+")) if (!hay.contains(w)) { ok = false; break; }
                if (!ok) continue;
            }
            r.add(e);
        }
        return r;
    }

    private void rebuildTree() {
        List<Entry> list = filtered();
        Map<Entry.Type, Map<String, List<Entry>>> byType = new EnumMap<>(Entry.Type.class);
        for (Entry e : list) byType.computeIfAbsent(e.type, k -> new TreeMap<>()).computeIfAbsent(e.group, k -> new ArrayList<>()).add(e);
        DefaultMutableTreeNode root = new DefaultMutableTreeNode();
        for (var te : byType.entrySet()) {
            int n = te.getValue().values().stream().mapToInt(List::size).sum();
            DefaultMutableTreeNode tn = new DefaultMutableTreeNode(te.getKey().title() + " (" + n + ")");
            for (var ge : te.getValue().entrySet()) {
                List<Entry> es = ge.getValue();
                es.sort(Comparator.comparing(x -> x.name.toLowerCase(Locale.ROOT)));
                DefaultMutableTreeNode parent = tn;
                if (te.getValue().size() > 1 || !ge.getKey().isEmpty()) {
                    parent = new DefaultMutableTreeNode((ge.getKey().isEmpty() ? "—" : ge.getKey()) + " (" + es.size() + ")");
                    tn.add(parent);
                }
                for (Entry e : es) parent.add(new DefaultMutableTreeNode(e, false));
            }
            root.add(tn);
        }
        treeModel.setRoot(root);
        for (int i = 0; i < root.getChildCount(); i++) tree.expandPath(new TreePath(new Object[]{root, root.getChildAt(i)}));
        if (!search.getText().isBlank() && list.size() <= 300)
            for (int r = 0; r < tree.getRowCount(); r++) tree.expandRow(r);
        status.setText(t("Models: ") + list.size() + (list.size() != ws.entries.size() ? t(" of ") + ws.entries.size() : ""));
        updateButtons();
    }

    private void selectEntry(Entry e) {
        Enumeration<TreeNode> en = ((DefaultMutableTreeNode) treeModel.getRoot()).depthFirstEnumeration();
        while (en.hasMoreElements()) {
            DefaultMutableTreeNode n = (DefaultMutableTreeNode) en.nextElement();
            if (n.getUserObject() == e) {
                TreePath tp = new TreePath(n.getPath());
                tree.setSelectionPath(tp);
                tree.scrollPathToVisible(tp);
                return;
            }
        }
    }

    private static void collect(DefaultMutableTreeNode n, Collection<Entry> out) {
        if (n.getUserObject() instanceof Entry e) { out.add(e); return; }
        for (int i = 0; i < n.getChildCount(); i++) collect((DefaultMutableTreeNode) n.getChildAt(i), out);
    }

    private List<Entry> selectedEntries() {
        LinkedHashSet<Entry> r = new LinkedHashSet<>();
        TreePath[] ps = tree.getSelectionPaths();
        if (ps != null) for (TreePath p : ps) collect((DefaultMutableTreeNode) p.getLastPathComponent(), r);
        return new ArrayList<>(r);
    }

    private void onSelect() {
        TreePath p = tree.getSelectionPath();
        if (p == null) return;
        Object u = ((DefaultMutableTreeNode) p.getLastPathComponent()).getUserObject();
        if (!(u instanceof Entry e) || e == currentEntry) return;
        currentEntry = e;
        texOverride = null;
        lastTexItem = auto;
        load(e, true);
    }

    private void reloadCurrent() {
        if (currentEntry != null) load(currentEntry, false);
    }

    private void load(Entry e, boolean refreshTextures) {
        int gen = GENERATION.incrementAndGet();
        BufferedImage tex = texOverride;
        preview.setMessage(t("Loading ") + e.name + "…");
        WORKER.submit(() -> {
            if (gen != GENERATION.get()) return;
            busy(t("Converting ") + e.name + "…");
            List<TextureGuess.Candidate> cands = null;
            MiModel m = null;
            String err = null;
            try {
                if (refreshTextures) cands = ws.textureCandidates(e);
                m = ws.export(e, tex);
            } catch (Throwable ex) {
                err = ex.toString();
                Log.warn(t("Conversion failed: ") + e.fullId() + ": " + ex);
                if (Log.verbose) ex.printStackTrace();
            }
            final List<TextureGuess.Candidate> fc = cands;
            final MiModel fm = m;
            final String ferr = err;
            SwingUtilities.invokeLater(() -> {
                if (gen != GENERATION.get()) return;
                if (fc != null) fillTextures(e, fc);
                if (fm != null) preview.setModel(fm);
                else preview.setMessage(t("Conversion failed:") + "\n" + ferr);
                info.setText(describe(e, fm, ferr));
                info.setCaretPosition(0);
            });
            idle(m != null ? e.name + ": " + t("elements ") + m.countShapes() : t("Error"));
        });
    }

    private void fillTextures(Entry e, List<TextureGuess.Candidate> cands) {
        texComboUpdating = true;
        try {
            texCombo.removeAllItems();
            boolean guessed = e.type == Entry.Type.ENTITY || e.type == Entry.Type.JAVA || e.type == Entry.Type.GEO || !cands.isEmpty();
            if (!guessed) {
                texCombo.addItem(fromModel);
                texCombo.setEnabled(false);
                return;
            }
            texCombo.addItem(auto);
            for (TextureGuess.Candidate c : cands) texCombo.addItem(c);
            texCombo.addItem(pick);
            texCombo.setSelectedIndex(0);
            texCombo.setEnabled(true);
        } finally {
            texComboUpdating = false;
        }
    }

    private void onTexture() {
        if (texComboUpdating || currentEntry == null) return;
        Object it = texCombo.getSelectedItem();
        if (it == null) return;
        BufferedImage img = null;
        if (it instanceof TextureGuess.Candidate c) {
            img = Img.read(c.source().read(c.path()));
            if (img == null) { Log.warn(t("Failed to read ") + c.path()); return; }
        } else if (pick.equals(it)) {
            JFileChooser fc = new JFileChooser(prefs.get("lastTex", System.getProperty("user.home")));
            fc.setFileFilter(new FileNameExtensionFilter("PNG (*.png)", "png"));
            if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) { restoreTexItem(); return; }
            File f = fc.getSelectedFile();
            if (f.getParentFile() != null) prefs.put("lastTex", f.getParent());
            try { img = Img.read(Files.readAllBytes(f.toPath())); } catch (Exception ex) { img = null; }
            if (img == null) {
                JOptionPane.showMessageDialog(this, t("Failed to read PNG: ") + f);
                restoreTexItem();
                return;
            }
            Log.info(t("Texture from file: ") + f);
        }
        lastTexItem = it;
        texOverride = img;
        load(currentEntry, false);
    }

    private void restoreTexItem() {
        texComboUpdating = true;
        texCombo.setSelectedItem(lastTexItem);
        texComboUpdating = false;
    }

    private String describe(Entry e, MiModel m, String err) {
        StringBuilder sb = new StringBuilder();
        sb.append(t("Model: ")).append(e.name).append('\n');
        sb.append("ID: ").append(e.fullId()).append('\n');
        sb.append(t("Type: ")).append(e.type.title()).append('\n');
        sb.append(t("Source: ")).append(e.source.displayName).append('\n');
        if (e.path != null) sb.append(t("File: ")).append(e.path).append('\n');
        if (m != null) {
            sb.append(t("Parts / elements: ")).append(m.parts.size()).append(" / ").append(m.countShapes()).append('\n');
            sb.append(t("Texture: ")).append(m.texture).append(" (").append((int) m.textureSize[0]).append('×').append((int) m.textureSize[1]).append(")\n");
            for (String n : m.notes) sb.append("• ").append(n).append('\n');
        }
        if (err != null) sb.append(t("Error: ")).append(err).append('\n');
        return sb.toString();
    }

    private Path chooseOutDir() {
        JFileChooser fc = new JFileChooser(prefs.get("lastOut", System.getProperty("user.home")));
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle(t("Folder to save .mimodel files"));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return null;
        File f = fc.getSelectedFile();
        prefs.put("lastOut", f.getPath());
        return f.toPath();
    }

    private void exportSelected() {
        List<Entry> sel = selectedEntries();
        if (sel.isEmpty()) {
            JOptionPane.showMessageDialog(this, t("Select models in the tree (Ctrl/Shift — several, a folder — everything inside)."));
            return;
        }
        runExport(sel);
    }

    private void exportFiltered() {
        List<Entry> list = filtered();
        if (list.isEmpty()) return;
        if (list.size() > 50 && JOptionPane.showConfirmDialog(this, I18n.f("Export %d models?", list.size()),
                t("Export"), JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        runExport(list);
    }

    private void runExport(List<Entry> list) {
        Path out = chooseOutDir();
        if (out == null) return;
        Entry cur = currentEntry;
        BufferedImage curTex = texOverride;
        boolean single = list.size() == 1;
        exportSel.setEnabled(false);
        exportAll.setEnabled(false);
        progress.setIndeterminate(false);
        progress.setMaximum(list.size());
        progress.setValue(0);
        progress.setVisible(true);
        WORKER.submit(() -> {
            int ok = 0, fail = 0, i = 0;
            Path last = null;
            for (Entry e : list) {
                final int done = i++;
                SwingUtilities.invokeLater(() -> {
                    progress.setValue(done);
                    status.setText(t("Exporting ") + (done + 1) + "/" + list.size() + ": " + e.name);
                });
                try {
                    MiModel m = ws.export(e, e == cur ? curTex : null);
                    Path dir = single ? out.resolve(m.name)
                            : out.resolve(e.type.key()).resolve(Workspace.safe(e.group.replace('/', '.'))).resolve(m.name);
                    last = MiWriter.write(m, dir, m.name);
                    ok++;
                } catch (Throwable ex) {
                    fail++;
                    Log.warn(t("Skipped ") + e.fullId() + ": " + ex.getMessage());
                }
            }
            final int fok = ok, ffail = fail;
            final Path openDir = single && last != null ? last.getParent() : out;
            Log.info(I18n.f("Export finished: %d done, %d skipped → %s", ok, fail, out));
            idle(t("Exported: ") + ok + (fail > 0 ? ", " + t("skipped: ") + fail : ""));
            SwingUtilities.invokeLater(() -> {
                Object[] opts = {t("Open folder"), "OK"};
                int r = JOptionPane.showOptionDialog(this, t("Done: ") + fok + (ffail > 0 ? "\n" + t("Skipped: ") + ffail + " " + t("(see the log)") : "")
                                + "\n\n" + t("In Mine-imator: “Import” (Ctrl+I) → choose the .mimodel file"),
                        t("Export"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE, null, opts, opts[1]);
                if (r == 0) openFolder(openDir);
            });
        });
    }
}
