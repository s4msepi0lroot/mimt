package mimt.scan;

import mimt.src.Source;

public class Entry {
    public enum Type {
        BLOCK("Blocks"), ITEM("Items"), JSON("Other JSON models"), ENTITY("Minecraft entities"),
        JAVA("Models from mod code (Java)"), GEO("Bedrock / GeckoLib (.geo.json)"), BBMODEL("Blockbench (.bbmodel)"),
        CEM("OptiFine CEM (.jem)");

        private final String title;

        public String title() { return mimt.util.I18n.t(title); }

        Type(String t) { title = t; }

        public String key() { return name().toLowerCase(); }
    }

    public final Type type;
    public final String id;
    public final String name;
    public final String group;
    public final Source source;
    public final String path;
    public Object extra;

    public Entry(Type type, String id, String name, String group, Source source, String path) {
        this.type = type;
        this.id = id;
        this.name = name;
        this.group = group;
        this.source = source;
        this.path = path;
    }

    public String fullId() { return type.key() + ":" + id; }

    @Override
    public String toString() { return name; }
}
