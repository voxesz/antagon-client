package studio.antagon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ModuleRegistry {
    static final String[] MODS = {
        "fps",
        "cps",
        "keys",
        "coords",
        "ping",
        "clock",
        "nohurtcam",
        "togglesprint",
        "perspective",
        "crosshair",
        "chat",
        "notitles",
        "radio",
        "reach",
        "combo",
        "hitbox",
        "hitcolor",
        "autotext",
        "scoreboard",
        "hitdelay",
        "fullbright",
        "ownnametag",
        "itemphysics",
        "itemsize",
        "armor",
        "potions",
        "oldanimations"
    };
    static final String[] NAMES = {
        "FPS",
        "CPS",
        "KEYSTROKES",
        "COORDENADAS",
        "PING",
        "RELÓGIO",
        "NO HURT CAM",
        "TOGGLE SPRINT",
        "PERSPECTIVE",
        "CROSSHAIR",
        "CHAT",
        "NO TITLES",
        "RÁDIO ANTAGON",
        "REACH DISPLAY",
        "COMBO COUNTER",
        "HITBOX",
        "HIT COLOR",
        "AUTO TEXT",
        "SCOREBOARD",
        "HIT DELAY FIX",
        "FULL BRIGHT",
        "NAMETAG PRÓPRIO",
        "ITEM PHYSICS",
        "ITEM SIZE",
        "ARMOR STATUS",
        "POTION STATUS",
        "1.7 ANIMATIONS"
    };
    static final boolean[] DEFAULT_ON = {
        true, true, true, false, true, false, false, false, false, false, false, false, false,
        false, false, false, false, false, false, false, false, true, false, false, false, false,
        false
    };
    static final int AUTOTEXT_SLOTS = 6;
    static final String COLOR_VALUES = "branco,vermelho,amarelo,verde,ciano";
    static final String[] SIZE = {"size", "TAMANHO", "range:50:200:5:%", "100%"};
    static final String[] COLOR_NAMES = {"branco", "vermelho", "amarelo", "verde", "ciano"};
    static final int[] COLORS = {0xFFF0EEE8, 0xFFEE1515, 0xFFFFD23F, 0xFF7BD66A, 0xFF5FD4E8};

    static final String[] CATEGORIES = {"TODOS", "HUD", "PVP", "UTILIDADES"};
    static final String[] DESCRIPTIONS = {
        "Quadros por segundo", "Cliques por segundo", "Teclas e cliques na tela",
        "Posição X, Y e Z", "Latência da conexão", "Hora sem sair do jogo",
        "Câmera estável ao levar hit", "Sprint com um toque", "Olhe ao redor livremente",
        "Formato, cor e tamanho", "Horário e repetidas", "Esconde títulos",
        "Rádios e playlists", "Distância do último hit", "Hits seguidos",
        "Limites visuais das entidades", "Cor do efeito de dano", "Atalhos de mensagens",
        "Tamanho, fundo e números", "Remove o bloqueio de clique", "Brilho máximo",
        "Seu nome em terceira pessoa", "Itens deitados no chão", "Tamanho dos itens na mão",
        "Armadura e durabilidade", "Efeitos ativos e duração", "Animações clássicas dos itens"
    };
    private static final int[] CATEGORY = {
        1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 2, 2, 2, 2, 3, 1, 2, 3, 3, 3, 2, 1, 1, 2
    };

    static int[] filter(int category) {
        int count = 0;
        for (int value : CATEGORY) if (category == 0 || value == category) count++;
        int[] matches = new int[count];
        int cursor = 0;
        for (int i = 0; i < MODS.length; i++)
            if (category == 0 || CATEGORY[i] == category) matches[cursor++] = i;
        return matches;
    }

    private static final Map<String, String[][]> OPTIONS = new HashMap<String, String[][]>();
    private static final Map<String, String> DEFAULT_VALUES = new HashMap<String, String>();
    private static final Map<String, Boolean> ENABLED = new HashMap<String, Boolean>();

    static {
        for (int i = 0; i < MODS.length; i++) {
            String mod = MODS[i];
            ENABLED.put(mod, DEFAULT_ON[i]);
            String[][] options = createOptions(mod);
            OPTIONS.put(mod, options);
            for (String[] option : options) {
                DEFAULT_VALUES.put(
                        mod + "." + option[0],
                        option.length > 3 ? option[3] : option[2].split(",")[0]);
            }
        }
    }

    private ModuleRegistry() {}

    static boolean defaultEnabled(String mod) {
        return Boolean.TRUE.equals(ENABLED.get(mod));
    }

    static String defaultValue(String key) {
        String value = DEFAULT_VALUES.get(key);
        return value == null ? "" : value;
    }

    static String[][] options(String mod) {
        String[][] options = OPTIONS.get(mod);
        return options == null ? new String[0][] : options;
    }

    static String cpsText(int left, int right, String buttons, boolean suffix) {
        String value =
                buttons.equals("esquerdo")
                        ? "" + left
                        : buttons.equals("direito") ? "" + right : left + " | " + right;
        return value + (suffix ? " CPS" : "");
    }

    private static String[][] createOptions(String mod) {
        if (mod.equals("nohurtcam")) return new String[0][];
        if (mod.equals("perspective"))
            return new String[][] {
                {"key", "TECLA", "key", "F"}, {"mode", "MODO", "segurar,alternar"}
            };
        if (mod.equals("crosshair"))
            return new String[][] {
                {"style", "ESTILO", "cruz,ponto,cruz + ponto"},
                {"color", "COR", COLOR_VALUES},
                {"size", "TAMANHO", "range:2:24:1:", "8"},
                {"thick", "ESPESSURA", "range:1:6:1:", "2"},
                {"gap", "ESPAÇO", "range:0:12:1:", "2"},
                {"outline", "CONTORNO", "on,off"}
            };
        if (mod.equals("chat"))
            return new String[][] {
                {"time", "HORÁRIO", "off,on"},
                {"stack", "EMPILHAR REPETIDAS", "on,off"},
                {"scale", "TAMANHO DO TEXTO", "range:25:100:5:%", "100%"},
                {"width", "LARGURA", "range:25:100:5:%", "100%"},
                {"lines", "LINHAS VISÍVEIS", "range:3:20:1:", "10"}
            };
        if (mod.equals("radio")) {
            List<String[]> radio =
                    new ArrayList<String[]>(
                            Arrays.asList(
                                    new String[][] {
                                        {"library", "RÁDIOS E PLAYLISTS", "action"},
                                        {"controls", "CONTROLES", "action"},
                                        {"volume", "VOLUME", "slider"},
                                        SIZE,
                                        {"art", "CAPA DO ÁLBUM", "on,off"},
                                        {"bg", "FUNDO", "on,off"},
                                        {"bar", "BARRA VERMELHA", "on,off"},
                                        {"prev", "TECLA: VOLTAR", "key", "NENHUMA"},
                                        {"pause", "TECLA: PAUSAR", "key", "NENHUMA"},
                                        {"next", "TECLA: PASSAR", "key", "NENHUMA"}
                                    }));
            return radio.toArray(new String[0][]);
        }
        if (mod.equals("hitbox"))
            return new String[][] {
                {"color", "COR", COLOR_VALUES},
                {"thick", "ESPESSURA", "range:1:6:1:", "2"},
                {"players", "SÓ JOGADORES", "off,on"},
                {"margin", "HITBOX REAL (+0.1)", "on,off"}
            };
        if (mod.equals("hitcolor"))
            return new String[][] {
                {"color", "COR", COLOR_VALUES, "vermelho"},
                {"alpha", "INTENSIDADE", "range:5:80:5:%", "30%"}
            };
        if (mod.equals("autotext")) {
            String[][] slots = new String[AUTOTEXT_SLOTS][];
            for (int i = 0; i < AUTOTEXT_SLOTS; i++)
                slots[i] = new String[] {"" + (i + 1), "", "autotext"};
            return slots;
        }
        if (mod.equals("scoreboard"))
            return new String[][] {
                SIZE,
                {"bg", "FUNDO", "on,off"},
                {"numbers", "NÚMEROS VERMELHOS", "on,off"},
                {"hide", "ESCONDER SCOREBOARD", "off,on"}
            };
        if (mod.equals("itemsize"))
            return new String[][] {{"scale", "TAMANHO NA MÃO", "range:25:150:5:%", "70%"}};
        if (mod.equals("armor"))
            return new String[][] {
                SIZE,
                {"layout", "ORIENTAÇÃO", "vertical,horizontal"},
                {"held", "ITEM NA MÃO", "on,off"},
                {"durability", "DURABILIDADE", "porcentagem,restante,off"},
                {"bg", "FUNDO", "on,off"}
            };
        if (mod.equals("potions"))
            return new String[][] {
                SIZE,
                {"names", "NOME DO EFEITO", "on,off"},
                {"blink", "AVISO AO TERMINAR", "on,off"},
                {"bg", "FUNDO", "on,off"}
            };
        if (mod.equals("oldanimations"))
            return new String[][] {
                {"block", "BLOCKHIT", "on,off"},
                {"bow", "ARCO", "on,off"},
                {"eating", "COMIDA E BEBIDA", "on,off"}
            };
        if (mod.equals("hitdelay")) return new String[0][];
        if (mod.equals("ownnametag") || mod.equals("itemphysics")) return new String[0][];
        if (mod.equals("fullbright"))
            return new String[][] {{"shadows", "SEM SOMBRA DAS ENTIDADES", "on,off"}};
        if (mod.equals("notitles"))
            return new String[][] {{"actionbar", "BARRA DE AÇÃO", "off,on"}};
        if (mod.equals("keys"))
            return new String[][] {
                SIZE,
                {"bg", "FUNDO", "on,off"},
                {"mouse", "BOTÕES DO MOUSE", "on,off"},
                {"space", "BARRA DE ESPAÇO", "on,off"},
                {"active", "COR AO APERTAR", "vermelho,branco,amarelo,verde,ciano"}
            };
        List<String[]> list =
                new ArrayList<String[]>(
                        Arrays.asList(
                                new String[][] {
                                    SIZE,
                                    {"bg", "FUNDO", "on,off"},
                                    {"bar", "BARRA VERMELHA", "on,off"},
                                    {"color", "COR DO TEXTO", COLOR_VALUES}
                                }));
        if (mod.equals("cps")) {
            list.add(0, new String[] {"buttons", "BOTÕES DO MOUSE", "ambos,esquerdo,direito"});
            list.add(1, new String[] {"suffix", "MOSTRAR CPS", "on,off"});
        }
        if (mod.equals("clock")) list.add(new String[] {"format", "FORMATO", "24h,12h"});
        if (mod.equals("togglesprint"))
            list.add(0, new String[] {"hud", "MOSTRAR NO HUD", "on,off"});
        return list.toArray(new String[0][]);
    }
}
