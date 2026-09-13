package fr.doctorwho.sgccontrol.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import fr.doctorwho.sgccontrol.network.GateActionPacket;
import fr.doctorwho.sgccontrol.network.GateSnapshotPacket;
import fr.doctorwho.sgccontrol.network.NetworkHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.lwjgl.glfw.GLFW;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * SGC monitor recreation driven by the real JSG gate state.
 *
 * The UI is independently redrawn with Minecraft primitives and JSG's own glyph icon resources.
 * No SWF/artwork/audio from the reference simulator is bundled.
 */
public final class SgcControlScreen extends Screen {
    private static final int DESIGN_W = 1600;
    private static final int DESIGN_H = 900;

    private static final int BLACK = 0xFF000000;
    private static final int CYAN = 0xFF12BFD3;
    private static final int CYAN_BRIGHT = 0xFF65F0F6;
    private static final int CYAN_DARK = 0xFF0B5964;
    private static final int CYAN_DEEP = 0xFF032B31;
    private static final int CREAM = 0xFFF3F0D9;
    private static final int PALE = 0xFFF4F2A7;
    private static final int GREY = 0xFF8F9A98;
    private static final int DARK_GREY = 0xFF263034;
    private static final int RED_LOCK = 0xFFD90C22;
    private static final int AMBER = 0xFFFFC95A;
    private static final int GREEN = 0xFF9AE8B6;

    private static final ResourceLocation GATE_FRAME_TEXTURE =
            new ResourceLocation("sgccontrol", "textures/gui/gate_frame.png");
    private static final ResourceLocation CHEVRON_IDLE_TEXTURE =
            new ResourceLocation("sgccontrol", "textures/gui/chevron_idle.png");
    private static final ResourceLocation CHEVRON_LOCKED_TEXTURE =
            new ResourceLocation("sgccontrol", "textures/gui/chevron_locked.png");

    private static final int FRAME_L = 226;
    private static final int FRAME_T = 102;
    private static final int FRAME_R = 1428;
    private static final int FRAME_B = 816;

    private static final int LEFT_X = 246;
    private static final int LEFT_TOP = 120;
    private static final int LEFT_W = 194;
    private static final int LEFT_BOTTOM = 503;

    private static final int CENTER_L = 460;
    private static final int CENTER_T = 120;
    private static final int CENTER_R = 1194;
    private static final int CENTER_B = 681;

    private static final int RIGHT_X = 1254;
    private static final int RIGHT_W = 151;
    private static final int SLOT_Y = 120;
    private static final int SLOT_H = 94;
    private static final int SLOT_GAP = 4;

    private static final int GATE_CX = 827;
    private static final int GATE_CY = 400;
    private static final int GATE_R = 257;

    // Physical chevron positions, clockwise from the top.
    // The SGC dialing order is NOT the physical clockwise order.
    private static final double[] CHEVRON_ANGLES = {-90.0, -50.0, -10.0, 30.0, 70.0, 110.0, 150.0, 190.0, 230.0};
    // 1: upper-right, 2: mid-right, 3: lower-right, 4: lower-left,
    // 5: mid-left, 6: upper-left, 7: bottom-right, 8: bottom-left, 9: top.
    private static final int[] CHEVRON_LOCK_ORDER = {1, 2, 3, 6, 7, 8, 4, 5, 0};

    private static final int KEYBOARD_BUTTON_X = 1038;
    private static final int KEYBOARD_BUTTON_Y = 714;
    private static final int KEYBOARD_BUTTON_W = 42;
    private static final int KEYBOARD_BUTTON_H = 26;

    private static final String[] MILKYWAY_KEY_GLYPHS = {
            "Sculptor", "Scorpius", "Centaurus", "Monoceros", "Pegasus", "Andromeda",
            "Serpens Caput", "Aries", "Libra", "Eridanus", "Leo Minor", "Hydra",
            "Sagittarius", "Sextans", "Scutum", "Pisces", "Virgo", "Bootes",
            "Auriga", "Corona Australis", "Gemini", "Leo", "Cetus", "Triangulum",
            "Aquarius", "Microscopium", "Equuleus", "Crater", "Perseus", "Cancer",
            "Norma", "Taurus", "Canis Minor", "Capricornus", "Lynx", "Orion",
            "Piscis Austrinus", "Aquila"
    };

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("MM/dd/yyyy HH:mm:ss");

    private final BlockPos terminalPos;
    private GateSnapshotPacket snap = new GateSnapshotPacket(
            false, "SCANNING", "N/A", "N/A", "N/A", "N/A",
            0, 0, 0, false, 0, List.of(), List.of(), "INITIALIZING SGC LINK"
    );

    private int selected = 0;
    // Resolve the initial highlighted destination once, then preserve the user's
    // cursor exactly.  This is important because row 0 is the local gate and the
    // network refresh runs every 10 ticks; older builds forced the cursor back to
    // the first remote (usually Earth) whenever the user moved above it.
    private boolean initialSelectionResolved = false;
    private int ticks = 0;
    private int sequenceStartTick = -1;
    private int lastDialedSymbols = 0;
    private int lastDialedChangeTick = 0;
    private boolean databaseOpen = false;
    private boolean controlKeyboardOpen = false;
    private boolean alertMode = false;
    // Frozen copy of the destination selected when DIAL is pressed.
    // JSG may rebuild/reorder its network list while the gate is dialing; keeping
    // this copy prevents the right-hand address display from jumping to another gate.
    private GateSnapshotPacket.TargetInfo dialDisplayTarget = null;
    private List<String> dialDisplaySymbols = List.of();
    private final List<String> manualSymbols = new ArrayList<>();
    private String selectedTargetFingerprint = "";

    public SgcControlScreen(BlockPos terminalPos) {
        super(Component.literal("SGC CONTROL"));
        this.terminalPos = terminalPos;
    }

    @Override
    protected void init() {
        send(GateActionPacket.Action.REFRESH, 0);
    }

    public void acceptSnapshot(GateSnapshotPacket packet) {
        int previous = this.snap.dialedSymbols();
        String previousFingerprint = selectedTargetFingerprint;
        GateSnapshotPacket.TargetInfo previousTarget = selectedTarget();
        if ((previousFingerprint == null || previousFingerprint.isBlank()) && previousTarget != null) {
            previousFingerprint = targetFingerprint(previousTarget);
        }
        this.snap = packet;

        if (packet.dialedSymbols() != previous) {
            lastDialedSymbols = packet.dialedSymbols();
            lastDialedChangeTick = ticks;
        }

        // Re-opening the terminal during an outbound dial must restore the exact
        // destination that was already being dialed. The server sends the latched
        // full outbound address in dialedAddressSymbols for this purpose.
        if (!stateLooksIncoming(packet.state())
                && (stateLooksDialing(packet.state()) || stateLooksActive(packet.state()) || packet.dialedSymbols() > 0)
                && dialDisplaySymbols.isEmpty()
                && packet.dialedAddressSymbols() != null
                && !packet.dialedAddressSymbols().isEmpty()) {
            dialDisplaySymbols = List.copyOf(packet.dialedAddressSymbols());
            sequenceStartTick = ticks;
        }

        if (packet.targets().isEmpty()) {
            selected = 0;
            selectedTargetFingerprint = "";
            initialSelectionResolved = false;
        } else {
            boolean resolvedFromFingerprint = false;
            if (previousFingerprint != null && !previousFingerprint.isBlank()) {
                for (int i = 0; i < packet.targets().size(); i++) {
                    if (previousFingerprint.equals(targetFingerprint(packet.targets().get(i)))) {
                        selected = i;
                        resolvedFromFingerprint = true;
                        break;
                    }
                }
            }

            if (!resolvedFromFingerprint && !initialSelectionResolved) {
                // Start on the first usable remote gate, but only once. After that, even
                // the LOCAL STARGATE row may stay selected while the periodic refreshes run.
                selected = firstDialableIndex(packet.targets());
                initialSelectionResolved = true;
            } else if (!resolvedFromFingerprint && selected >= packet.targets().size()) {
                selected = Math.max(0, packet.targets().size() - 1);
            }

            GateSnapshotPacket.TargetInfo now = selectedTarget();
            selectedTargetFingerprint = now == null ? "" : targetFingerprint(now);
            initialSelectionResolved = true;
        }

        // Once the real gate has fully returned to idle, clear the frozen dialing target
        // so the seven destination slots become empty again, just like the reference monitor.
        if (dialDisplayTarget != null && packet.dialedSymbols() == 0 && !stateLooksDialing(packet.state())
                && !stateLooksActive(packet.state()) && sequenceStartTick >= 0
                && ticks - sequenceStartTick > 80) {
            dialDisplayTarget = null;
            dialDisplaySymbols = List.of();
            sequenceStartTick = -1;
        }
    }

    private void send(GateActionPacket.Action action, int value) {
        send(action, value, "");
    }

    private void send(GateActionPacket.Action action, int value, String payload) {
        NetworkHandler.CHANNEL.sendToServer(new GateActionPacket(terminalPos, action, value,
                payload == null ? "" : payload));
    }

    @Override
    public void tick() {
        super.tick();
        ticks++;
        if (ticks % 10 == 0) send(GateActionPacket.Action.REFRESH, 0);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, BLACK);

        float scale = uiScale();
        float ox = uiOffsetX(scale);
        float oy = uiOffsetY(scale);

        g.pose().pushPose();
        g.pose().translate(ox, oy, 0);
        g.pose().scale(scale, scale, 1f);

        if (databaseOpen) {
            drawDatabase(g, mouseX, mouseY, scale, ox, oy);
        } else {
            drawMonitor(g, partialTick);
            if (controlKeyboardOpen) {
                // The control keyboard is a foreground panel on the real SGC monitor.
                // Render it above the gate/chevrons so nothing from the gate can bleed through.
                g.pose().pushPose();
                g.pose().translate(0, 0, 200);
                drawControlKeyboard(g);
                g.pose().popPose();
            } else {
                drawKeyboardToggle(g);
            }
        }

        g.pose().popPose();
        super.render(g, mouseX, mouseY, partialTick);
    }

    // ---------------------------------------------------------------------
    // MAIN MONITOR - matches the reference monitor layout.

    private void drawMonitor(GuiGraphics g, float partialTick) {
        drawOuterFrame(g);
        drawCenterMonitorFrame(g);
        drawLeftSequencePanel(g);
        drawStatusCluster(g);
        drawGate(g, partialTick);
        if (isIncomingActivation()) drawIncomingAlert(g);
        drawRightGlyphSlots(g);
        drawBottomModules(g);
    }

    private void drawOuterFrame(GuiGraphics g) {
        rectOutline(g, FRAME_L, FRAME_T, FRAME_R, FRAME_B, CYAN, 2);
    }

    private void drawCenterMonitorFrame(GuiGraphics g) {
        rectOutline(g, CENTER_L, CENTER_T, CENTER_R, CENTER_B, CYAN, 2);

        g.fill(CENTER_L, CENTER_T, CENTER_L + 31, CENTER_T + 40, 0xFF1498A8);
        g.fill(CENTER_R - 31, CENTER_T, CENTER_R, CENTER_T + 40, 0xFF1498A8);
        g.fill(CENTER_L + 31, CENTER_T + 2, CENTER_R - 31, CENTER_T + 7, CYAN);
        g.fill(CENTER_L + 31, CENTER_T + 36, CENTER_R - 31, CENTER_T + 40, CYAN_DARK);

        g.fill(CENTER_L, CENTER_B - 40, CENTER_L + 31, CENTER_B, 0xFF1498A8);
        g.fill(CENTER_R - 31, CENTER_B - 40, CENTER_R, CENTER_B, 0xFF1498A8);
        g.fill(CENTER_L + 31, CENTER_B - 40, CENTER_R - 31, CENTER_B - 36, CYAN_DARK);
        g.fill(CENTER_L + 31, CENTER_B - 5, CENTER_R - 31, CENTER_B, CYAN);

        line(g, CENTER_L, 199, 562, 199, CYAN, 2);
        line(g, 562, 199, 603, 158, CYAN, 2);
        line(g, 603, 158, 627, 158, CYAN, 2);
        line(g, CENTER_L, 361, 540, 361, CYAN, 2);
        line(g, CENTER_L, 467, 517, 467, CYAN, 2);
        line(g, 517, 467, 542, 531, CYAN, 2);
        line(g, 542, 531, 568, 531, CYAN, 2);

        line(g, CENTER_R, 199, 1092, 199, CYAN, 2);
        line(g, 1092, 199, 1051, 158, CYAN, 2);
        line(g, 1051, 158, 1027, 158, CYAN, 2);
        line(g, CENTER_R, 361, 1114, 361, CYAN, 2);
        line(g, CENTER_R, 467, 1137, 467, CYAN, 2);
        line(g, 1137, 467, 1112, 531, CYAN, 2);
        line(g, 1112, 531, 1086, 531, CYAN, 2);
    }

    private void drawLeftSequencePanel(GuiGraphics g) {
        rectOutline(g, LEFT_X, LEFT_TOP, LEFT_X + LEFT_W, LEFT_BOTTOM, CYAN, 2);

        // In the real monitor the upper-left display is empty while idle and fills with
        // changing diagnostic numbers during a dialing sequence.
        if (!isDialing() && sequenceStartTick < 0) return;

        int lines = 17;
        int y = LEFT_TOP + 22;
        for (int i = 0; i < lines; i++) {
            long seed = 100000000L + Math.abs((long) (i + 3) * 9781L + (ticks / 5L) * (i + 7L) * 137L);
            String text = Long.toString(seed % 9999999999L);
            int c = i == (ticks / 5) % lines ? CREAM : (i % 3 == 0 ? GREY : CYAN_DARK);
            g.drawCenteredString(font, text, LEFT_X + LEFT_W / 2, y, c);
            y += 17;
        }
    }

    private void drawStatusCluster(GuiGraphics g) {
        line(g, LEFT_X - 5, 514, LEFT_X + LEFT_W + 5, 514, CYAN, 2);
        line(g, LEFT_X - 5, 525, LEFT_X - 5, 682, CYAN, 2);
        line(g, LEFT_X + LEFT_W + 5, 525, LEFT_X + LEFT_W + 5, 682, CYAN, 2);

        drawOctagonMatrix(g, 301, 565, 36, 0);
        drawOctagonMatrix(g, 386, 565, 36, 1);
        drawOctagonMatrix(g, 301, 646, 36, 2);
        drawOctagonMatrix(g, 386, 646, 36, 3);

        line(g, 287, 511, 311, 511, CYAN, 2);
        line(g, 372, 511, 396, 511, CYAN, 2);
        line(g, 253, 532, 253, 548, CYAN, 2);
        line(g, 433, 532, 433, 548, CYAN, 2);
        line(g, 253, 659, 253, 675, CYAN, 2);
        line(g, 433, 659, 433, 675, CYAN, 2);
    }

    private void drawOctagonMatrix(GuiGraphics g, int cx, int cy, int r, int pattern) {
        int[][] p = new int[8][2];
        for (int i = 0; i < 8; i++) {
            double a = Math.toRadians(22.5 + i * 45.0);
            p[i][0] = (int) Math.round(cx + Math.cos(a) * r);
            p[i][1] = (int) Math.round(cy + Math.sin(a) * r);
        }
        for (int i = 0; i < 8; i++) {
            int n = (i + 1) % 8;
            line(g, p[i][0], p[i][1], p[n][0], p[n][1], CYAN, 2);
        }

        int[][] offsets = {{-11,-10},{4,-10},{-11,4},{4,4},{-3,-3}};
        for (int i = 0; i < offsets.length; i++) {
            boolean on = ((i + pattern * 2 + ticks / 30) % 4) != 0;
            int c = on ? CREAM : 0xFF5C625E;
            int x = cx + offsets[i][0];
            int y = cy + offsets[i][1];
            g.fill(x, y, x + 8, y + 8, c);
        }
    }

    private void drawGate(GuiGraphics g, float partialTick) {
        boolean active = isActive();
        boolean dialing = isDialing();

        if (active) drawEventHorizon(g);
        else {
            g.fill(GATE_CX - 215, GATE_CY - 215, GATE_CX + 216, GATE_CY + 216, BLACK);
            strokeCircle(g, GATE_CX, GATE_CY, 214, CYAN_DARK, 1, 180);
        }

        // High-resolution original vector-style gate frame.  It is rendered from a
        // transparent texture generated for this addon so the rails and 39 cells stay
        // smooth at GUI scale instead of looking like coarse Minecraft line primitives.
        int frameSize = (GATE_R + 25) * 2;
        int frameX = GATE_CX - frameSize / 2;
        int frameY = GATE_CY - frameSize / 2;
        RenderSystem.enableBlend();
        // GuiGraphics.blit(width,height,texWidth,texHeight) samples only a region whose
        // source size equals the destination size.  Because gate_frame.png is 1024x1024,
        // using frameSize here cropped the texture to its upper-left corner.  Draw the
        // complete 1024x1024 image at native source size and scale the pose instead.
        g.pose().pushPose();
        g.pose().translate(frameX, frameY, 0);
        float gateTextureScale = frameSize / 1024.0f;
        g.pose().scale(gateTextureScale, gateTextureScale, 1.0f);
        g.blit(GATE_FRAME_TEXTURE, 0, 0, 0, 0, 1024, 1024, 1024, 1024);
        g.pose().popPose();
        RenderSystem.disableBlend();

        // The moving ring is represented by a subtle rotating set of inner markers while
        // the heavy outer frame/chevron housings remain fixed, as on the SGC monitor.
        double ringAngle = snap.ringAngle();
        if ((snap.spinning() || dialing) && Math.abs(ringAngle) < 0.001)
            ringAngle = (ticks + partialTick) * 1.5;
        double cell = 360.0 / 39.0;
        for (int i = 0; i < 39; i++) {
            double a = i * cell + ringAngle;
            line(g,
                    polarX(GATE_CX, GATE_R - 58, a), polarY(GATE_CY, GATE_R - 58, a),
                    polarX(GATE_CX, GATE_R - 49, a), polarY(GATE_CY, GATE_R - 49, a),
                    CYAN_DARK, 1);
        }

        int expectedChevrons = expectedChevronCount();
        int[] chevronOrder = chevronLockOrder(expectedChevrons);
        int realLockedCount = Math.min(expectedChevrons, Math.max(0, snap.dialedSymbols()));
        int sinceLock = Math.max(0, ticks - lastDialedChangeTick);

        // SGC lock choreography: for every symbol the TOP chevron is the temporary
        // locking mechanism. It lights once, goes dark, and only then does the actual
        // destination chevron latch. For a 7-chevron address the seventh/final chevron
        // is the TOP one itself (8/9-chevron addresses use the lower extension chevrons
        // before finally ending at the top).
        boolean lockTransition = dialing && realLockedCount > 0 && sinceLock < 8;
        int visibleLockedCount = lockTransition ? Math.max(0, realLockedCount - 1) : realLockedCount;
        boolean topLockPulse = lockTransition && sinceLock < 5;

        for (int physical = 0; physical < 9; physical++) {
            boolean locked = false;
            for (int step = 0; step < visibleLockedCount && step < chevronOrder.length; step++) {
                if (chevronOrder[step] == physical) {
                    locked = true;
                    break;
                }
            }

            int anchorY = polarY(GATE_CY, GATE_R + 1, CHEVRON_ANGLES[physical]);
            if (controlKeyboardOpen && anchorY > 610) continue;

            boolean current = topLockPulse && physical == 0;
            drawChevronClamp(g, CHEVRON_ANGLES[physical], locked, current);
        }

        // Keep the selected destination glyph visible in the centre for the whole
        // step.  It changes only when JSG advances to the next chevron.
        GateSnapshotPacket.TargetInfo target = displayDialTarget();
        List<String> sequenceSymbols = !dialDisplaySymbols.isEmpty()
                ? dialDisplaySymbols
                : (target == null ? List.of() : dialSymbolsForTarget(target));
        if (dialing && !isIncomingActivation() && !sequenceSymbols.isEmpty()) {
            int symbolIndex = Math.min(Math.max(0, snap.dialedSymbols()), sequenceSymbols.size() - 1);
            String symbol = sequenceSymbols.get(symbolIndex);
            drawJsgGlyph(g, symbol, GATE_CX - 62, GATE_CY - 62, 124, CREAM);
        }

        if (!active) {
            line(g, GATE_CX - 51, GATE_CY, GATE_CX + 51, GATE_CY, CYAN, 1);
            line(g, GATE_CX, GATE_CY - 51, GATE_CX, GATE_CY + 51, CYAN, 1);
            g.fill(GATE_CX - 2, GATE_CY - 2, GATE_CX + 3, GATE_CY + 3, CYAN_BRIGHT);
        }
    }

    private void drawChevronClamp(GuiGraphics g, double angle, boolean locked, boolean current) {
        ResourceLocation texture = (locked || current) ? CHEVRON_LOCKED_TEXTURE : CHEVRON_IDLE_TEXTURE;
        int size = current ? 76 : 72;
        int anchorX = polarX(GATE_CX, GATE_R + 1, angle);
        int anchorY = polarY(GATE_CY, GATE_R + 1, angle);

        g.pose().pushPose();
        g.pose().translate(anchorX, anchorY, 20);
        // Texture is authored pointing upward. -90 degrees is therefore its zero rotation.
        g.pose().mulPose(Axis.ZP.rotationDegrees((float) (angle + 90.0)));
        RenderSystem.enableBlend();
        // Same rule as the gate frame: render the full 256x256 chevron texture and
        // scale it down, otherwise Minecraft samples only a small top-left crop.
        g.pose().pushPose();
        float chevronTextureScale = size / 256.0f;
        g.pose().scale(chevronTextureScale, chevronTextureScale, 1.0f);
        g.blit(texture, -128, -128, 0, 0, 256, 256, 256, 256);
        g.pose().popPose();
        RenderSystem.disableBlend();

        // The temporary top lock is steady, not blinking.
        if (current) {
            g.fill(-3, -size / 2 + 13, 4, 2, CREAM);
        }
        g.pose().popPose();
    }

    private void drawEventHorizon(GuiGraphics g) {
        int phase = (ticks / 2) % 24;
        g.fill(GATE_CX - 214, GATE_CY - 214, GATE_CX + 215, GATE_CY + 215, 0xFF03171E);
        for (int rr = 32; rr <= 208; rr += 8) {
            int v = (rr / 8 + phase) % 5;
            int c = switch (v) {
                case 0 -> 0xFF4BE8FF;
                case 1 -> 0xFF1389AD;
                case 2 -> 0xFF0A5370;
                case 3 -> 0xFF1AA7C5;
                default -> 0xFF073A50;
            };
            strokeCircle(g, GATE_CX, GATE_CY, rr, c, v == 0 ? 2 : 1, 160);
        }
    }

    private void drawRightGlyphSlots(GuiGraphics g) {
        if (isIncomingActivation()) {
            int lit = isActive() ? 7 : Math.min(7, Math.max(0, snap.dialedSymbols()));
            for (int i = 0; i < 7; i++) {
                int y = SLOT_Y + i * (SLOT_H + SLOT_GAP);
                int border = i < lit ? RED_LOCK : CYAN;
                rectOutline(g, RIGHT_X, y, RIGHT_X + RIGHT_W, y + SLOT_H, border, 2);
                if (i < lit) {
                    g.fill(RIGHT_X + 3, y + 3, RIGHT_X + RIGHT_W - 3, y + SLOT_H - 3, 0x660F0004);
                    g.drawString(font, Integer.toString(i + 1), RIGHT_X - 15, y + 37, RED_LOCK, false);
                    g.fill(RIGHT_X + 16, y + 18, RIGHT_X + RIGHT_W - 16, y + SLOT_H - 18, 0x66D90C22);
                }
            }
            return;
        }

        GateSnapshotPacket.TargetInfo target = displayDialTarget();
        List<String> symbols;
        if (!dialDisplaySymbols.isEmpty()) {
            // The monitor must show the destination the operator selected, not a transient
            // local/default address that JSG may expose while rebuilding its dial buffer.
            symbols = dialDisplaySymbols;
        } else {
            symbols = target == null ? List.of() : dialSymbolsForTarget(target);
        }
        int locked = Math.min(snap.dialedSymbols(), Math.min(7, symbols.size()));

        for (int i = 0; i < 7; i++) {
            int y = SLOT_Y + i * (SLOT_H + SLOT_GAP);
            rectOutline(g, RIGHT_X, y, RIGHT_X + RIGHT_W, y + SLOT_H, CYAN, 2);
            if (i < locked && i < symbols.size()) {
                g.drawString(font, Integer.toString(i + 1), RIGHT_X - 15, y + 37, CREAM, false);
                drawJsgGlyph(g, symbols.get(i), RIGHT_X + 30, y + 10, 72, CREAM);
            }
        }
    }

    private void drawBottomModules(GuiGraphics g) {
        drawEnergyModule(g);
        drawMatrixModule(g);
        drawStateModule(g);
    }

    private void drawEnergyModule(GuiGraphics g) {
        int x = 239, y = 692, w = 368, h = 104;
        rectOutline(g, x, y, x + w, y + h, CYAN, 2);

        double ratio = snap.maxEnergy() > 0
                ? Math.max(0, Math.min(1, (double) snap.energy() / snap.maxEnergy()))
                : 0;

        // Ten columns, all strictly clipped inside the original monitor frame.
        int[] bars = {50, 72, 61, 30, 44, 36, 25, 13, 63, 28};
        int barW = 26;
        int gap = 8;
        int bx = x + 10;
        int bottom = y + h - 12;

        for (int i = 0; i < bars.length; i++) {
            int fullH = bars[i];
            int left = bx + i * (barW + gap);
            int right = left + barW;
            int top = bottom - fullH;

            g.fill(left, top, right, bottom, 0xFF021419);

            int litH = (int) Math.round(fullH * Math.max(0.12, ratio));
            int litTop = bottom - litH;
            g.fill(left, litTop, right, bottom, 0xFF0A3941);

            // Continuous SGC-style yellow scan marker: each bar has its own phase and
            // moves smoothly up/down instead of sitting at a fixed height.
            double wave = (Math.sin((ticks * 0.10) + i * 0.72) + 1.0) * 0.5;
            int markerMin = top + 3;
            int markerMax = Math.max(markerMin, bottom - 6);
            int markerY = markerMin + (int) Math.round((markerMax - markerMin) * wave);
            g.fill(left + 1, markerY, right - 1, markerY + 3, PALE);
        }
    }

    private void drawMatrixModule(GuiGraphics g) {
        int x = 619, y = 692, w = 183, h = 104;
        rectOutline(g, x, y, x + w, y + h, CYAN, 2);
        int cols = 8, rows = 4, cellW = 23, cellH = 26;
        for (int row = 0; row < rows; row++) {
            for (int col = 0; col < cols; col++) {
                int x1 = x + col * cellW;
                int y1 = y + row * cellH;
                rectOutline(g, x1, y1, x1 + cellW, y1 + cellH, CYAN_DARK, 1);
                boolean on = ((row * 13 + col * 7 + ticks / 12 + snap.dialedSymbols()) % 5) < 2;
                if (on) g.fill(x1 + 2, y1 + 2, x1 + cellW - 2, y1 + cellH - 2, CREAM);
            }
        }
    }

    private void drawIncomingAlert(GuiGraphics g) {
        int boxW = 500;
        int boxH = 150;
        int x1 = GATE_CX - (boxW / 2);
        int y1 = GATE_CY - (boxH / 2);
        int x2 = x1 + boxW;
        int y2 = y1 + boxH;
        int frame = ((ticks / 6) % 2 == 0) ? RED_LOCK : 0xFF781014;
        g.fill(x1, y1, x2, y2, 0xB0140000);
        rectOutline(g, x1, y1, x2, y2, frame, 3);
        rectOutline(g, x1 + 6, y1 + 6, x2 - 6, y2 - 6, frame, 1);
        drawBigCenteredText(g, "OFFWORLD", GATE_CX, y1 + 30, 3.25f, 0xFFFFE6E6);
        drawBigCenteredText(g, "ACTIVATION", GATE_CX, y1 + 82, 3.25f, 0xFFFFE6E6);
    }

    private void drawStateModule(GuiGraphics g) {
        GateSnapshotPacket.TargetInfo target = displayDialTarget();
        int expected;
        if (!dialDisplaySymbols.isEmpty()) expected = Math.max(7, Math.min(9, dialDisplaySymbols.size()));
        else expected = target == null ? 7 : Math.max(7, Math.min(9,
                target.symbolsNeeded() <= 0 ? visualSymbols(target).size() : target.symbolsNeeded()));
        boolean seq = isDialing() || (sequenceStartTick >= 0 && ticks - sequenceStartTick < 200);

        if (isIncomingActivation()) {
            if ((ticks / 10) % 2 == 0)
                drawBigText(g, "ALERT", 814, 708, 2.8f, RED_LOCK);
        } else if (isActive()) {
            drawBigText(g, "CONNECTED", 813, 701, 2.7f, CYAN_BRIGHT);
        } else if (seq && snap.dialedSymbols() >= expected) {
            if (((ticks - lastDialedChangeTick) / 10) % 2 == 0)
                drawBigText(g, "LOCKED", 814, 703, 3.3f, RED_LOCK);
        } else if (seq) {
            drawBigText(g, "SEQUENCE", 815, 700, 2.3f, CYAN);
            drawBigText(g, "IN PROGRESS", 815, 726, 2.3f, CYAN);
        } else {
            drawBigText(g, "IDLE", 812, 700, 3.4f, CYAN_DARK);
        }
    }

    // ---------------------------------------------------------------------
    // SGC control keyboard overlay. The small keyboard button mirrors the
    // simulator control used to reveal the dialing/iris/list controls.

    private void drawKeyboardToggle(GuiGraphics g) {
        int x = KEYBOARD_BUTTON_X;
        int y = KEYBOARD_BUTTON_Y;
        g.fill(x, y, x + KEYBOARD_BUTTON_W, y + KEYBOARD_BUTTON_H, 0xFF292D30);
        rectOutline(g, x, y, x + KEYBOARD_BUTTON_W, y + KEYBOARD_BUTTON_H,
                controlKeyboardOpen ? CREAM : CYAN, 2);
        int kx = x + 8;
        int ky = y + 8;
        rectOutline(g, kx, ky, kx + 25, ky + 14, CREAM, 1);
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 5; col++) {
                int px = kx + 3 + col * 4;
                int py = ky + 3 + row * 5;
                g.fill(px, py, px + 2, py + 2, CREAM);
            }
        }
        g.fill(kx + 4, ky + 11, kx + 21, ky + 13, CREAM);
    }

    private void drawControlKeyboard(GuiGraphics g) {
        int x = 308;
        int y = 610;
        int w = 956;
        int h = 276;
        // Opaque overlay so gate chevrons/buttons stay visually behind the control panel.
        g.fill(x, y, x + w, y + h, 0xFF090A0B);
        rectOutline(g, x, y, x + w, y + h, 0xFF6E7478, 2);

        int keyW = 66;
        int keyH = 50;
        int gap = 2;
        int startX = x + 54;
        int startY = y + 18;
        int glyphIndex = 0;
        int[] rowCounts = {10, 10, 9, 9};
        int[] rowOffsets = {0, 18, 39, 62};
        for (int row = 0; row < rowCounts.length; row++) {
            int rx = startX + rowOffsets[row];
            int ry = startY + row * (keyH + gap);
            for (int col = 0; col < rowCounts[row] && glyphIndex < MILKYWAY_KEY_GLYPHS.length; col++) {
                int kx = rx + col * (keyW + gap);
                drawKeyboardKey(g, kx, ry, keyW, keyH, MILKYWAY_KEY_GLYPHS[glyphIndex], glyphIndex);
                glyphIndex++;
            }
        }

        int controlsX = x + 800;
        int controlsY = y + 18;
        drawControlKey(g, controlsX, controlsY, 130, 42, "LIST");
        drawControlKey(g, controlsX, controlsY + 47, 130, 42, "DIAL ON/OFF");
        drawControlKey(g, controlsX, controlsY + 94, 130, 42, alertMode ? "ALERT ON" : "ALERT OFF");
        drawControlKey(g, controlsX, controlsY + 141, 130, 42, "SHUT OFF");
        drawControlKey(g, controlsX, controlsY + 188, 130, 42, "IRIS");

        g.drawString(font, "SYSTEM GLYPHS", x + 8, y + 8, CREAM, false);
        g.drawString(font, "MANUAL ADDRESS " + manualSymbols.size() + "/8", x + 8, y + h - 38, CYAN_BRIGHT, false);
        g.drawString(font, "ESC", x + 11, y + h - 20, CREAM, false);
    }

    private void drawKeyboardKey(GuiGraphics g, int x, int y, int w, int h, String symbol, int index) {
        boolean chosen = manualSymbols.contains(symbol);
        g.fill(x, y, x + w, y + h, chosen ? 0xFF263A3D : 0xFF5B5E60);
        rectOutline(g, x, y, x + w, y + h, chosen ? CYAN_BRIGHT : 0xFF7D8184, chosen ? 2 : 1);
        drawJsgGlyph(g, symbol, x + 16, y + 5, 38, chosen ? CYAN_BRIGHT : 0xFFECEDE8);
        String label = Character.toString((char) ('A' + (index % 26)));
        g.drawString(font, label, x + w - 12, y + h - 13, 0xFFFFFFFF, false);
    }

    private void drawControlKey(GuiGraphics g, int x, int y, int w, int h, String label) {
        g.fill(x, y, x + w, y + h, 0xFF515457);
        rectOutline(g, x, y, x + w, y + h, 0xFF81868A, 1);
        g.drawCenteredString(font, label, x + w / 2, y + 16, 0xFFFFFFFF);
    }

    // ---------------------------------------------------------------------
    // TAB DATABASE - separate screen like the reference simulator.

    private void drawDatabase(GuiGraphics g, int mouseX, int mouseY, float scale, float ox, float oy) {
        int x0 = 227;
        int x1 = 1425;

        drawBigText(g, "SYSTEM ADMINISTRATOR", x0, 18, 1.25f, CYAN);
        String clock = LocalDateTime.now().format(CLOCK);
        g.drawString(font, clock, 1185, 22, CYAN, false);
        line(g, x0, 39, x1, 39, CYAN, 2);

        drawBigText(g, "SYSTEM GLYPHS / ARCHIVE / PRIMARY / PLANETARY DATABASE / DETAIL", x0, 64, 1.14f, CYAN);
        line(g, x0, 84, 1243, 84, CYAN, 2);

        List<GateSnapshotPacket.TargetInfo> entries = snap.targets();
        int pageSize = 7;
        int pageStart = entries.isEmpty() ? 0 : (selected / pageSize) * pageSize;
        int rowTop = 98;
        int rowH = 88;

        if (entries.isEmpty()) {
            rectOutline(g, x0, 98, 1243, 706, CYAN, 2);
            g.drawCenteredString(font, "NO STARGATE ENTRIES REGISTERED IN JSG NETWORK", 735, 380, AMBER);
        }

        for (int row = 0; row < pageSize && pageStart + row < entries.size(); row++) {
            int idx = pageStart + row;
            GateSnapshotPacket.TargetInfo t = entries.get(idx);
            int y = rowTop + row * rowH;
            boolean sel = idx == selected;
            drawDatabaseRow(g, t, idx, y, rowH, sel);
        }

        drawDatabaseScroll(g, entries, pageStart, pageSize);
        drawDatabaseAlgorithmPanel(g);
        drawDatabaseNav(g);
    }

    private void drawDatabaseRow(GuiGraphics g, GateSnapshotPacket.TargetInfo t, int index, int y, int h, boolean selectedRow) {
        int rowL = 227;
        int rowR = 1243;
        int leftCardR = 458;
        int glyphStart = 867;
        int glyphCellW = 62;

        int border = selectedRow ? CREAM : CYAN;
        rectOutline(g, rowL, y, rowR, y + h - 6, border, selectedRow ? 3 : 2);

        g.fill(rowL + 3, y + 3, leftCardR, y + h - 9, PALE);
        int text = 0xFF121212;
        g.drawString(font, String.format(Locale.ROOT, "%03d/%02d # %s", index + 101, index + 1, crop(t.name(), 25)), rowL + 7, y + 7, text, false);
        g.drawString(font, "LIFE SUPPORT", rowL + 7, y + 24, text, false);
        g.drawString(font, "POTENTIAL: " + (t.dialable() ? "ACCEPTABLE" : t.local() ? "LOCAL" : "UNAVAILABLE"), rowL + 7, y + 39, text, false);
        g.drawString(font, "STATUS: " + (t.local() ? "LOCAL GATE" : t.dialable() ? "READY" : "NO ROUTE"), rowL + 7, y + 54, text, false);
        g.drawString(font, "SECTOR: " + crop(t.dimension(), 21), rowL + 7, y + 69, text, false);

        int midX = 462;
        g.drawString(font, "POWER FLUX NORMAL AND WITHIN ACCEPTABLE RANGE // RESOURCE ID=OK", midX, y + 7, CREAM, false);
        g.drawString(font, "POWER LEVEL ...", midX, y + 21, CREAM, false);
        g.drawString(font, "CASE " + pseudoHex(t, 8), midX, y + 35, CREAM, false);
        g.drawString(font, "FILE REF LIST " + pseudoHex(t, 6), midX, y + 49, CREAM, false);
        g.drawString(font, "STATUS : " + (t.dialable() ? "OK" : t.local() ? "LOCAL" : "LOCKED"), 694, y + 21, CREAM, false);
        g.drawString(font, "RESEARCH FILE", 694, y + 35, CREAM, false);
        g.drawString(font, "MALP : " + (t.local() ? "N/A" : "OK"), 694, y + 49, CREAM, false);

        List<String> glyphs = visualSymbols(t);
        int show = Math.min(6, glyphs.size());
        for (int i = 0; i < 6; i++) {
            int gx = glyphStart + i * glyphCellW;
            rectOutline(g, gx, y + 31, gx + glyphCellW, y + h - 8, CYAN, 1);
            if (i < show) drawJsgGlyph(g, glyphs.get(i), gx + 8, y + 34, 46, CREAM);
        }

        if (!t.dialable() && !t.local()) {
            rectOutline(g, rowL, y, rowR, y + h - 6, RED_LOCK, 2);
        }
    }

    private void drawDatabaseScroll(GuiGraphics g, List<GateSnapshotPacket.TargetInfo> entries, int pageStart, int pageSize) {
        int x = 1254;
        int y = 83;
        int w = 170;
        int h = 624;
        rectOutline(g, x, y, x + w, y + h, CYAN, 2);
        g.fill(x + w - 17, y + 4, x + w - 3, y + h - 4, 0xFF6FC7D0);
        g.fill(x + w - 17, y + 4, x + w - 3, y + 22, 0xFF1799A9);
        g.fill(x + w - 17, y + h - 22, x + w - 3, y + h - 4, 0xFF1799A9);

        int yy = y + 22;
        for (int i = 0; i < pageSize && pageStart + i < entries.size(); i++) {
            GateSnapshotPacket.TargetInfo t = entries.get(pageStart + i);
            g.drawString(font, "0x " + pseudoHex(t, 10), x + 14, yy, CYAN, false);
            yy += 88;
        }
    }

    private void drawDatabaseAlgorithmPanel(GuiGraphics g) {
        int x = 227, y = 729, w = 1198, h = 135;
        rectOutline(g, x, y, x + w, y + h, CYAN, 2);
        drawBigText(g, "BILINEAR SEARCH ALGORITHM", x + 5, y + 8, 1.8f, CREAM);
        g.drawString(font, "SEARCH PATH SYSTEM / DATA / ARCHIVE / ...", x + 5, y + 37, CREAM, false);
        g.drawString(font, "EXECUTING VARIABLE SEARCH ALGORITHM", x + 800, y + 15, CREAM, false);
        g.drawString(font, "SOURCE LEVEL 1 PRIVILEGE", x + 904, y + 37, CREAM, false);

        GateSnapshotPacket.TargetInfo t = selectedTarget();
        List<String> glyphs = t == null ? List.of() : visualSymbols(t);
        int gy = y + 53;
        for (int i = 0; i < 7; i++) {
            int gx = x + 5 + i * 74;
            rectOutline(g, gx, gy, gx + 72, gy + 58, CYAN, 1);
            if (i < glyphs.size()) drawJsgGlyph(g, glyphs.get(i), gx + 10, gy + 7, 48, CREAM);
        }
        g.drawString(font, "1", x + 523, gy + 4, CREAM, false);

        for (int i = 0; i < 7; i++) {
            int gx = x + 650 + i * 74;
            rectOutline(g, gx, gy, gx + 72, gy + 58, CYAN, 1);
            if (!glyphs.isEmpty()) {
                String sym = glyphs.get((i + Math.max(1, glyphs.size() / 2)) % glyphs.size());
                drawJsgGlyph(g, sym, gx + 10, gy + 7, 48, CREAM);
            }
        }
        g.drawString(font, "2", x + 620, gy + 35, CREAM, false);
    }

    private void drawDatabaseNav(GuiGraphics g) {
        String[] labels = {"PREV", "NEXT", "EDIT", "FILE", "VIEW", "DETAIL", "LOG", "HELP", "QUIT"};
        int x = 227;
        int y = 872;
        int totalW = 1198;
        int bw = totalW / labels.length;
        for (int i = 0; i < labels.length; i++) {
            int bx = x + i * bw;
            rectOutline(g, bx, y, i == labels.length - 1 ? x + totalW : bx + bw, y + 30, CYAN, 2);
            g.drawCenteredString(font, labels[i], bx + bw / 2, y + 9, CYAN);
        }
    }

    // ---------------------------------------------------------------------
    // Glyph rendering.

    private void drawJsgGlyph(GuiGraphics g, String symbol, int x, int y, int size, int fallbackColor) {
        if (symbol == null || symbol.isBlank()) return;

        if (symbol.toLowerCase(Locale.ROOT).contains("point of origin") || symbol.equalsIgnoreCase("origin")) {
            drawOriginGlyph(g, x, y, size, fallbackColor);
            return;
        }

        if (snap.gateType().toUpperCase(Locale.ROOT).contains("MILKY")) {
            String file = symbol.toLowerCase(Locale.ROOT)
                    .replace('-', '_')
                    .replace(' ', '_')
                    .replace("'", "")
                    .replaceAll("[^a-z0-9_]", "");
            try {
                ResourceLocation texture = new ResourceLocation("jsg", "textures/gui/symbol/milkyway/" + file + ".png");
                RenderSystem.enableBlend();
                g.blit(texture, x, y, 0, 0, size, size, size, size);
                RenderSystem.disableBlend();
                return;
            } catch (Throwable ignored) {
                // If the texture path is unavailable (custom symbol pack / another gate family),
                // fall through to the deterministic line glyph below.
            }
        }

        drawProceduralGlyph(g, symbol, x, y, size, fallbackColor);
    }

    private void drawOriginGlyph(GuiGraphics g, int x, int y, int size, int color) {
        int cx = x + size / 2;
        int top = y + size / 8;
        int r = Math.max(5, size / 10);
        strokeCircle(g, cx, top + r, r, color, Math.max(1, size / 30), 48);
        line(g, cx, top + r * 2, x + size / 4, y + size * 3 / 4, color, Math.max(1, size / 28));
        line(g, cx, top + r * 2, x + size * 3 / 4, y + size * 3 / 4, color, Math.max(1, size / 28));
        line(g, x + size / 4, y + size * 3 / 4, x + size / 7, y + size * 3 / 4, color, Math.max(1, size / 28));
        line(g, x + size * 3 / 4, y + size * 3 / 4, x + size * 6 / 7, y + size * 3 / 4, color, Math.max(1, size / 28));
    }

    private void drawProceduralGlyph(GuiGraphics g, String symbol, int x, int y, int size, int color) {
        int hash = symbol.toLowerCase(Locale.ROOT).hashCode();
        int points = 6 + Math.floorMod(hash, 4);
        int cx = x + size / 2;
        int cy = y + size / 2;
        int r = Math.max(10, size / 3);
        int prevX = cx;
        int prevY = cy - r;
        for (int i = 1; i <= points; i++) {
            double a = -Math.PI / 2 + i * (Math.PI * 2 / points);
            int rr = r - Math.floorMod(hash >> (i % 16), Math.max(2, r / 3));
            int px = (int) Math.round(cx + Math.cos(a) * rr);
            int py = (int) Math.round(cy + Math.sin(a) * rr);
            line(g, prevX, prevY, px, py, color, Math.max(1, size / 35));
            prevX = px;
            prevY = py;
        }
        line(g, prevX, prevY, cx, cy - r, color, Math.max(1, size / 35));
    }

    // ---------------------------------------------------------------------
    // Input.

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            databaseOpen = !databaseOpen;
            controlKeyboardOpen = false;
            return true;
        }

        if (databaseOpen) {
            if (keyCode == GLFW.GLFW_KEY_DOWN && !snap.targets().isEmpty()) {
                selected = (selected + 1) % snap.targets().size();
                selectedTargetFingerprint = targetFingerprint(selectedTarget());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_UP && !snap.targets().isEmpty()) {
                selected = (selected - 1 + snap.targets().size()) % snap.targets().size();
                selectedTargetFingerprint = targetFingerprint(selectedTarget());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN && !snap.targets().isEmpty()) {
                selected = Math.min(snap.targets().size() - 1, selected + 7);
                selectedTargetFingerprint = targetFingerprint(selectedTarget());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_PAGE_UP && !snap.targets().isEmpty()) {
                selected = Math.max(0, selected - 7);
                selectedTargetFingerprint = targetFingerprint(selectedTarget());
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                startDialSelected();
                return true;
            }
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                databaseOpen = false;
                return true;
            }
        }

        if (keyCode == GLFW.GLFW_KEY_K) {
            controlKeyboardOpen = !controlKeyboardOpen;
            return true;
        }
        if (controlKeyboardOpen && keyCode == GLFW.GLFW_KEY_ESCAPE) {
            controlKeyboardOpen = false;
            return true;
        }

        if (keyCode == GLFW.GLFW_KEY_I) {
            send(GateActionPacket.Action.TOGGLE_IRIS, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_C) {
            send(GateActionPacket.Action.CLOSE, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_A || keyCode == GLFW.GLFW_KEY_BACKSPACE) {
            send(GateActionPacket.Action.ABORT, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_R) {
            send(GateActionPacket.Action.REFRESH, 0);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            startDialSelected();
            return true;
        }

        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        double mx = logicalMouseX(mouseX);
        double my = logicalMouseY(mouseY);

        if (!databaseOpen) {
            if (inside(mx, my, KEYBOARD_BUTTON_X, KEYBOARD_BUTTON_Y, KEYBOARD_BUTTON_W, KEYBOARD_BUTTON_H)) {
                controlKeyboardOpen = !controlKeyboardOpen;
                return true;
            }
            if (controlKeyboardOpen && handleControlKeyboardClick(mx, my, button)) return true;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        List<GateSnapshotPacket.TargetInfo> entries = snap.targets();
        int pageSize = 7;
        int pageStart = entries.isEmpty() ? 0 : (selected / pageSize) * pageSize;
        if (mx >= 227 && mx <= 1243 && my >= 98 && my < 98 + pageSize * 88) {
            int row = (int) ((my - 98) / 88.0);
            int idx = pageStart + row;
            if (idx >= 0 && idx < entries.size()) {
                if (idx == selected && button == 0) startDialSelected();
                else {
                    selected = idx;
                    selectedTargetFingerprint = targetFingerprint(selectedTarget());
                }
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean handleControlKeyboardClick(double mx, double my, int button) {
        if (button != 0) return false;
        int x = 308;
        int y = 610;

        // Clickable glyph keys: left-click toggles a glyph in the manual address buffer.
        int keyW = 66;
        int keyH = 50;
        int gap = 2;
        int startX = x + 54;
        int startY = y + 18;
        int glyphIndex = 0;
        int[] rowCounts = {10, 10, 9, 9};
        int[] rowOffsets = {0, 18, 39, 62};
        for (int row = 0; row < rowCounts.length; row++) {
            int rx = startX + rowOffsets[row];
            int ry = startY + row * (keyH + gap);
            for (int col = 0; col < rowCounts[row] && glyphIndex < MILKYWAY_KEY_GLYPHS.length; col++) {
                int kx = rx + col * (keyW + gap);
                if (inside(mx, my, kx, ry, keyW, keyH)) {
                    String symbol = MILKYWAY_KEY_GLYPHS[glyphIndex];
                    if (manualSymbols.contains(symbol)) {
                        manualSymbols.remove(symbol);
                    } else if (manualSymbols.size() < 8) {
                        manualSymbols.add(symbol);
                    }
                    return true;
                }
                glyphIndex++;
            }
        }

        int controlsX = x + 800;
        int controlsY = y + 18;
        if (inside(mx, my, controlsX, controlsY, 130, 42)) {
            databaseOpen = true;
            controlKeyboardOpen = false;
            return true;
        }
        if (inside(mx, my, controlsX, controlsY + 47, 130, 42)) {
            if (isActive()) send(GateActionPacket.Action.CLOSE, 0);
            else if (isDialing()) send(GateActionPacket.Action.ABORT, 0);
            else if (manualSymbols.size() >= 6) startManualDial();
            else startDialSelected();
            return true;
        }
        if (inside(mx, my, controlsX, controlsY + 94, 130, 42)) {
            alertMode = !alertMode;
            return true;
        }
        if (inside(mx, my, controlsX, controlsY + 141, 130, 42)) {
            if (isDialing()) send(GateActionPacket.Action.ABORT, 0);
            else send(GateActionPacket.Action.CLOSE, 0);
            return true;
        }
        if (inside(mx, my, controlsX, controlsY + 188, 130, 42)) {
            send(GateActionPacket.Action.TOGGLE_IRIS, 0);
            return true;
        }
        return false;
    }

    private static boolean inside(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    private void startDialSelected() {
        GateSnapshotPacket.TargetInfo t = selectedTarget();
        if (t == null || !t.dialable()) return;

        // Freeze the exact visual address and send a stable destination fingerprint.
        // The server no longer relies only on a list index, because JSG may reorder its
        // network map between the client snapshot and the click that starts the dial.
        dialDisplayTarget = copyTarget(t);
        dialDisplaySymbols = List.copyOf(dialSymbolsForTarget(t));

        // Send the exact glyph sequence selected in the database.  Using only a list index
        // or network fingerprint allowed JSG's refreshed target order to fall back to the
        // first/Earth entry.  The server now receives the selected address itself.
        send(GateActionPacket.Action.DIAL_MANUAL, 0, String.join("\u001F", dialDisplaySymbols));
        selectedTargetFingerprint = targetFingerprint(t);
        databaseOpen = false;
        controlKeyboardOpen = false;
        sequenceStartTick = ticks;
        lastDialedSymbols = 0;
        lastDialedChangeTick = ticks;
    }

    private void startManualDial() {
        if (manualSymbols.size() < 6 || manualSymbols.size() > 8) return;
        List<String> full = new ArrayList<>(manualSymbols);
        full.add("Point of Origin");
        dialDisplaySymbols = List.copyOf(full);
        dialDisplayTarget = new GateSnapshotPacket.TargetInfo(
                "MANUAL ADDRESS", "MANUAL", String.join(", ", full), snap.gateType(),
                0, 0, 0, false, true, full.size(), List.copyOf(full));
        send(GateActionPacket.Action.DIAL_MANUAL, 0, String.join("\u001F", full));
        selectedTargetFingerprint = "";
        controlKeyboardOpen = false;
        databaseOpen = false;
        sequenceStartTick = ticks;
        lastDialedSymbols = 0;
        lastDialedChangeTick = ticks;
    }

    /**
     * Number of chevrons for the address currently being displayed/dialed.
     * The selected destination's calculated JSG value wins; the frozen visual symbol
     * list is the fallback.
     */
    private int expectedChevronCount() {
        GateSnapshotPacket.TargetInfo target = displayDialTarget();
        if (target != null && target.symbolsNeeded() >= 7 && target.symbolsNeeded() <= 9)
            return target.symbolsNeeded();
        if (!dialDisplaySymbols.isEmpty())
            return Math.max(7, Math.min(9, dialDisplaySymbols.size()));
        if (target != null && target.symbols() != null && !target.symbols().isEmpty())
            return Math.max(7, Math.min(9, target.symbols().size()));
        return 7;
    }

    /**
     * Physical SGC chevron sequence.  Standard 7-chevron addresses finish at the TOP.
     * 8-chevron addresses insert the lower-right extension before TOP; 9-chevron
     * addresses insert lower-right and lower-left before TOP.
     */
    private static int[] chevronLockOrder(int expectedChevrons) {
        if (expectedChevrons <= 7)
            return new int[]{1, 2, 3, 6, 7, 8, 0};
        if (expectedChevrons == 8)
            return new int[]{1, 2, 3, 6, 7, 8, 4, 0};
        return new int[]{1, 2, 3, 6, 7, 8, 4, 5, 0};
    }

    // ---------------------------------------------------------------------
    // State / helpers.

    private GateSnapshotPacket.TargetInfo selectedTarget() {
        if (snap.targets().isEmpty()) return null;
        int i = Math.max(0, Math.min(selected, snap.targets().size() - 1));
        return snap.targets().get(i);
    }

    private GateSnapshotPacket.TargetInfo displayDialTarget() {
        return dialDisplayTarget != null ? dialDisplayTarget : selectedTarget();
    }

    private static String targetFingerprint(GateSnapshotPacket.TargetInfo t) {
        if (t == null) return "";
        return t.dimension() + "|" + t.x() + "|" + t.y() + "|" + t.z() + "|" + t.address() + "|" + t.name();
    }

    private static GateSnapshotPacket.TargetInfo copyTarget(GateSnapshotPacket.TargetInfo t) {
        if (t == null) return null;
        return new GateSnapshotPacket.TargetInfo(
                t.name(), t.dimension(), t.address(), t.type(),
                t.x(), t.y(), t.z(), t.local(), t.dialable(), t.symbolsNeeded(),
                t.symbols() == null ? List.of() : List.copyOf(t.symbols())
        );
    }

    private static boolean stateLooksDialing(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return s.contains("dial") || s.contains("spin") || s.contains("incoming");
    }

    private static boolean stateLooksActive(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return s.contains("engag") || s.contains("connect") || s.contains("open") || s.contains("established");
    }

    private static boolean stateLooksIncoming(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return s.contains("incoming") || s.contains("offworld");
    }

    private boolean isIncomingActivation() {
        return stateLooksIncoming(snap.state());
    }

    private List<String> visualSymbols(GateSnapshotPacket.TargetInfo t) {
        if (t == null || t.symbols() == null) return List.of();
        return t.symbols();
    }

    /**
     * Builds the exact source-gate dialing sequence for a database target.
     * JSG stores up to eight address glyphs while a normal dial needs 7/8/9 symbols
     * including the local Point of Origin.  Rebuilding it here makes the database
     * selection deterministic and prevents a refresh from silently dialing Earth.
     */
    private List<String> dialSymbolsForTarget(GateSnapshotPacket.TargetInfo t) {
        if (t == null || t.symbols() == null || t.symbols().isEmpty()) return List.of();

        List<String> raw = new ArrayList<>(t.symbols());
        while (!raw.isEmpty() && isOriginName(raw.get(raw.size() - 1))) raw.remove(raw.size() - 1);

        int needed = t.symbolsNeeded();
        if (needed < 7 || needed > 9) {
            needed = Math.max(7, Math.min(9, raw.size() + 1));
        }

        List<String> out = new ArrayList<>(needed);
        int glyphCount = Math.min(raw.size(), needed - 1);
        for (int i = 0; i < glyphCount; i++) out.add(raw.get(i));
        if (out.size() < 6) return List.of();
        out.add("Point of Origin");
        return List.copyOf(out);
    }

    private static boolean isOriginName(String name) {
        if (name == null) return false;
        String s = name.trim().toLowerCase(Locale.ROOT);
        return s.equals("origin") || s.contains("point of origin");
    }

    private boolean isDialing() {
        return stateLooksDialing(snap.state()) || snap.dialedSymbols() > 0;
    }

    private boolean isActive() {
        return stateLooksActive(snap.state());
    }

    private static boolean hasDialable(List<GateSnapshotPacket.TargetInfo> list) {
        for (GateSnapshotPacket.TargetInfo t : list) if (t.dialable()) return true;
        return false;
    }

    private static int firstDialableIndex(List<GateSnapshotPacket.TargetInfo> list) {
        for (int i = 0; i < list.size(); i++) if (list.get(i).dialable()) return i;
        return 0;
    }

    private static String pseudoHex(GateSnapshotPacket.TargetInfo t, int len) {
        long seed = ((long) t.name().hashCode() << 32) ^ t.dimension().hashCode() ^ ((long)t.x() << 20) ^ ((long)t.z() << 4);
        String raw = Long.toHexString(seed).toUpperCase(Locale.ROOT).replace("-", "");
        if (raw.length() < len) raw = (raw + "0000000000000000").substring(0, len);
        return raw.substring(0, Math.min(len, raw.length()));
    }

    private String crop(String text, int maxChars) {
        if (text == null) return "";
        if (text.length() <= maxChars) return text;
        return text.substring(0, Math.max(0, maxChars - 1)) + "…";
    }

    private void drawBigText(GuiGraphics g, String text, int x, int y, float scale, int color) {
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1f);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    private void drawBigCenteredText(GuiGraphics g, String text, int centerX, int y, float scale, int color) {
        int width = Math.round(font.width(text) * scale);
        drawBigText(g, text, centerX - width / 2, y, scale, color);
    }

    private static int polarX(int cx, int r, double degrees) {
        return (int) Math.round(cx + Math.cos(Math.toRadians(degrees)) * r);
    }

    private static int polarY(int cy, int r, double degrees) {
        return (int) Math.round(cy + Math.sin(Math.toRadians(degrees)) * r);
    }

    private static void rectOutline(GuiGraphics g, int left, int top, int right, int bottom, int color, int thickness) {
        g.fill(left, top, right, top + thickness, color);
        g.fill(left, bottom - thickness, right, bottom, color);
        g.fill(left, top, left + thickness, bottom, color);
        g.fill(right - thickness, top, right, bottom, color);
    }

    private static void line(GuiGraphics g, int x1, int y1, int x2, int y2, int color, int thickness) {
        int dx = x2 - x1;
        int dy = y2 - y1;
        int steps = Math.max(Math.abs(dx), Math.abs(dy));
        if (steps == 0) {
            g.fill(x1, y1, x1 + thickness, y1 + thickness, color);
            return;
        }
        double sx = dx / (double) steps;
        double sy = dy / (double) steps;
        double x = x1;
        double y = y1;
        int half = Math.max(0, thickness / 2);
        for (int i = 0; i <= steps; i++) {
            int px = (int) Math.round(x);
            int py = (int) Math.round(y);
            g.fill(px - half, py - half, px - half + thickness, py - half + thickness, color);
            x += sx;
            y += sy;
        }
    }

    private static void strokeCircle(GuiGraphics g, int cx, int cy, int r, int color, int thickness, int segments) {
        int prevX = cx + r;
        int prevY = cy;
        for (int i = 1; i <= segments; i++) {
            double a = i * (Math.PI * 2.0 / segments);
            int x = (int) Math.round(cx + Math.cos(a) * r);
            int y = (int) Math.round(cy + Math.sin(a) * r);
            line(g, prevX, prevY, x, y, color, thickness);
            prevX = x;
            prevY = y;
        }
    }

    private float uiScale() {
        return Math.max(0.1f, Math.min(width / (float) DESIGN_W, height / (float) DESIGN_H));
    }

    private float uiOffsetX(float scale) {
        return (width - DESIGN_W * scale) / 2f;
    }

    private float uiOffsetY(float scale) {
        return (height - DESIGN_H * scale) / 2f;
    }

    private double logicalMouseX(double screenX) {
        float s = uiScale();
        return (screenX - uiOffsetX(s)) / s;
    }

    private double logicalMouseY(double screenY) {
        float s = uiScale();
        return (screenY - uiOffsetY(s)) / s;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
