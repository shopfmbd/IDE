package com.fahim.cloudide;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;

/**
 * A small manifest merger, the part of the Android Gradle plugin that library manifests depend on. Libraries (AARs
 * and local library modules) declare permissions, providers (androidx.startup), services, receivers and activities
 * in their own manifest. Without merging them the app builds but those pieces are silently missing at run time.
 *
 * Rules: library entries are added after the app's own, never replace them; an entry is skipped when the app (or an
 * earlier library) already declares the same android:name; relative class names (".Foo", "Foo") are made absolute
 * with the library's package; ${applicationId} is replaced; tools: attributes are dropped. tools:node="remove" and
 * placeholders other than applicationId are not interpreted. Pure Java (DOM), so it also runs on a JDK.
 */
final class ManifestMerge {
    static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String TOOLS = "http://schemas.android.com/tools";

    private static final Set<String> TOP = new HashSet<>(Arrays.asList("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m",
            "uses-feature", "permission", "permission-group", "permission-tree", "queries"));
    private static final Set<String> APP = new HashSet<>(Arrays.asList("activity", "activity-alias", "service", "receiver", "provider",
            "meta-data", "uses-library", "uses-native-library"));
    private static final Set<String> CLASS_ATTRS = new HashSet<>(Arrays.asList("name", "parentActivityName", "targetActivity"));

    private ManifestMerge() { }

    /** libs: pairs {packageName, manifestText}. Returns the merged manifest text of the app. */
    static String merge(String app, String applicationId, List<String[]> libs, List<String> notes) throws Exception {
        if (libs.isEmpty()) return app;
        Document d = parse(app);
        Element root = d.getDocumentElement();
        Element application = child(root, "application");
        if (application == null) {
            application = d.createElement("application");
            root.appendChild(application);
        }
        Set<String> have = new HashSet<>();
        String appPkg = root.getAttribute("package");
        index(root, appPkg, applicationId, have);
        index(application, appPkg, applicationId, have);
        // tools:node="remove" in the app's own manifest: keep those entries (and children of a provider) out of the merge
        Set<String> removed = new HashSet<>();
        // providers by key: a second library declaring the same provider (androidx.startup) adds its children to it
        java.util.Map<String, Element> providers = new java.util.HashMap<>();
        for (Node n = application.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String tag = e.getTagName();
            String k = key(e, tag, appPkg, applicationId);
            if (isRemove(e)) removed.add(k);
            if (tag.equals("provider")) {
                providers.put(k, e);
                for (Node c = e.getFirstChild(); c != null; c = c.getNextSibling())
                    if (c instanceof Element && isRemove((Element) c)) removed.add(k + "/" + childKey((Element) c));
            }
        }
        int added = 0;
        for (String[] lib : libs) {
            Document ld;
            try {
                ld = parse(lib[1]);
            } catch (Exception e) {
                notes.add("manifest of " + lib[0] + " not merged: " + e.getMessage());
                continue;
            }
            Element lroot = ld.getDocumentElement();
            String pkg = lroot.getAttribute("package");
            if (pkg.isEmpty()) pkg = lib[0];
            for (Node n = lroot.getFirstChild(); n != null; n = n.getNextSibling()) {
                if (!(n instanceof Element)) continue;
                Element e = (Element) n;
                String tag = e.getTagName();
                if (TOP.contains(tag)) {
                    if (!removed.contains(key(e, tag, pkg, applicationId)) && take(d, e, pkg, applicationId, have, tag)) {
                        root.insertBefore(d.importNode(prepared(e, pkg, applicationId, tag), true), application);
                        added++;
                    }
                } else if (tag.equals("application")) {
                    for (Node a = e.getFirstChild(); a != null; a = a.getNextSibling()) {
                        if (!(a instanceof Element) || !APP.contains(((Element) a).getTagName())) continue;
                        Element ae = (Element) a;
                        String atag = ae.getTagName();
                        String ak = key(ae, atag, pkg, applicationId);
                        if (removed.contains(ak)) continue;
                        if (atag.equals("provider") && providers.containsKey(ak)) {
                            added += mergeProvider(d, providers.get(ak), ae, ak, applicationId, removed);
                        } else if (take(d, ae, pkg, applicationId, have, atag)) {
                            Node in = d.importNode(prepared(ae, pkg, applicationId, atag), true);
                            application.appendChild(in);
                            if (atag.equals("provider")) providers.put(ak, (Element) in);
                            added++;
                        }
                    }
                }
            }
        }
        stripRemoved(application);
        notes.add("manifest merge: " + added + " entr" + (added == 1 ? "y" : "ies") + " added from " + libs.size() + " librar" + (libs.size() == 1 ? "y" : "ies"));
        return write(d);
    }

    private static Document parse(String text) throws Exception {
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        try {
            // library manifests are downloaded: no DOCTYPE, so no external entities
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        } catch (Exception ignored) {
            // a parser that does not know this feature (Android's) does not resolve external entities anyway
        }
        return f.newDocumentBuilder().parse(new ByteArrayInputStream(text.getBytes("UTF-8")));
    }

    private static String write(Document d) throws Exception {
        Transformer t = TransformerFactory.newInstance().newTransformer();
        t.setOutputProperty(OutputKeys.INDENT, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        StringWriter w = new StringWriter();
        t.transform(new DOMSource(d), new StreamResult(w));
        return w.toString();
    }

    private static Element child(Element e, String tag) {
        for (Node n = e.getFirstChild(); n != null; n = n.getNextSibling())
            if (n instanceof Element && ((Element) n).getTagName().equals(tag)) return (Element) n;
        return null;
    }

    private static String key(Element e, String tag, String pkg, String appId) {
        String name = e.getAttributeNS(ANDROID, "name");
        if (name.isEmpty() && tag.equals("uses-feature")) name = "glEsVersion:" + e.getAttributeNS(ANDROID, "glEsVersion");
        if (tag.equals("provider")) {
            String auth = e.getAttributeNS(ANDROID, "authorities");
            if (!auth.isEmpty()) return tag + ":auth:" + auth.replace("${applicationId}", appId);
        }
        if (tag.equals("queries")) return tag + ":" + e.getTextContent().replaceAll("\\s+", "");
        return tag + ":" + absolute(name, pkg, tag);
    }

    private static boolean isRemove(Element e) {
        return "remove".equals(e.getAttributeNS(TOOLS, "node")) || "removeAll".equals(e.getAttributeNS(TOOLS, "node"));
    }

    private static String childKey(Element c) {
        return c.getTagName() + ":" + c.getAttributeNS(ANDROID, "name");
    }

    /** Adds the children (meta-data ...) of a library's copy of a provider to the provider already in the manifest. */
    private static int mergeProvider(Document d, Element into, Element from, String pk, String appId, Set<String> removed) {
        Set<String> have = new HashSet<>();
        for (Node c = into.getFirstChild(); c != null; c = c.getNextSibling()) if (c instanceof Element) have.add(childKey((Element) c));
        int n = 0;
        for (Node c = from.getFirstChild(); c != null; c = c.getNextSibling()) {
            if (!(c instanceof Element)) continue;
            Element ce = (Element) c;
            String ck = childKey(ce);
            if (isRemove(ce) || removed.contains(pk + "/" + ck) || !have.add(ck)) continue;
            Element copy = (Element) d.importNode(ce, true);
            fixChild(copy, appId);
            into.appendChild(copy);
            n++;
        }
        return n;
    }

    /** Takes elements marked tools:node="remove" out of the finished manifest. */
    private static void stripRemoved(Element parent) {
        List<Node> kill = new ArrayList<>();
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            if (isRemove((Element) n)) kill.add(n);
            else stripRemoved((Element) n);
        }
        for (Node n : kill) parent.removeChild(n);
    }

    private static void index(Element parent, String pkg, String appId, Set<String> have) {
        for (Node n = parent.getFirstChild(); n != null; n = n.getNextSibling()) {
            if (!(n instanceof Element)) continue;
            Element e = (Element) n;
            String tag = e.getTagName();
            if (TOP.contains(tag) || APP.contains(tag)) have.add(key(e, tag, pkg, appId));
        }
    }

    private static boolean take(Document d, Element e, String pkg, String appId, Set<String> have, String tag) {
        return have.add(key(e, tag, pkg, appId));
    }

    private static String absolute(String name, String pkg, String tag) {
        if (name.isEmpty()) return name;
        if (name.startsWith(".")) return pkg + name;
        if (!name.contains(".") && (tag.equals("activity") || tag.equals("service") || tag.equals("receiver") || tag.equals("provider") || tag.equals("activity-alias")))
            return pkg + "." + name;
        return name;
    }

    /** A copy of e with absolute class names, ${applicationId} filled in and tools: attributes removed. */
    private static Element prepared(Element e, String pkg, String appId, String tag) {
        Element c = (Element) e.cloneNode(true);
        fix(c, pkg, appId, tag);
        return c;
    }

    private static void fix(Element c, String pkg, String appId, String tag) {
        NamedNodeMap at = c.getAttributes();
        List<Attr> drop = new ArrayList<>();
        for (int i = 0; i < at.getLength(); i++) {
            Attr a = (Attr) at.item(i);
            if (TOOLS.equals(a.getNamespaceURI())) {
                drop.add(a);
                continue;
            }
            String v = a.getValue();
            if (v.contains("${applicationId}")) a.setValue(v.replace("${applicationId}", appId));
            if (ANDROID.equals(a.getNamespaceURI()) && CLASS_ATTRS.contains(a.getLocalName())) a.setValue(absolute(a.getValue(), pkg, tag));
        }
        for (Attr a : drop) c.removeAttributeNode(a);
        for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) fixChild((Element) n, appId);
    }

    private static void fixChild(Element c, String appId) {
        NamedNodeMap at = c.getAttributes();
        List<Attr> drop = new ArrayList<>();
        for (int i = 0; i < at.getLength(); i++) {
            Attr a = (Attr) at.item(i);
            if (TOOLS.equals(a.getNamespaceURI())) drop.add(a);
            else if (a.getValue().contains("${applicationId}")) a.setValue(a.getValue().replace("${applicationId}", appId));
        }
        for (Attr a : drop) c.removeAttributeNode(a);
        for (Node n = c.getFirstChild(); n != null; n = n.getNextSibling()) if (n instanceof Element) fixChild((Element) n, appId);
    }
}
