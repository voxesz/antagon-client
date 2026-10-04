package studio.antagon;

import com.google.gson.*;

public final class RadioCatalogTest {
    private static void check(boolean value) {
        if (!value) throw new AssertionError();
    }

    public static void main(String[] args) {
        String json =
                "{serverTime:0,tracks:["
                    + "{id:'a',title:'A',artist:'Test',mediaKey:'tracks/00000000-0000-4000-8000-000000000001.mp3',durationMs:10000},"
                    + "{id:'b',title:'B',artist:'Test',mediaKey:'tracks/00000000-0000-4000-8000-000000000002.mp3',durationMs:20000}],"
                    + "collections:[{id:'live',name:'Test',mode:'radio',trackIds:['a','b'],schedules:[{startsAt:1000,trackIds:['a','b']}]}]}";
        RadioCatalog catalog = new RadioCatalog(new JsonParser().parse(json).getAsJsonObject(), 0);
        RadioCatalog.Collection c = catalog.collection("live");
        check(catalog.live(c, 999) == null);
        check(catalog.live(c, 1000).track.id.equals("a"));
        check(catalog.live(c, 11000).track.id.equals("b"));
        check(Math.abs(catalog.live(c, 16500).offset - 5.5) < .00001);
        check(catalog.live(c, 31000).track.id.equals("a"));
        check(!catalog.live(c, 1000).key.equals(catalog.live(c, 31000).key));
        c.schedules.add(
                new RadioCatalog.Schedule(
                        new JsonParser()
                                .parse("{startsAt:40000,trackIds:['b','a']}")
                                .getAsJsonObject()));
        check(catalog.live(c, 39999).track.id.equals("a"));
        check(catalog.live(c, 40000).track.id.equals("b"));
        check(catalog.live(c, 40000).offset == 0);
        catalog.tracks.remove("b");
        check(catalog.live(c, 1000) == null);
        System.out.println(
                "Java radio OK: boundaries, cycles, schedule changes and missing-track"
                    + " synchronization.");
    }
}
