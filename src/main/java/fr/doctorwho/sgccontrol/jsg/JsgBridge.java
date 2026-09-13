package fr.doctorwho.sgccontrol.jsg;

import fr.doctorwho.sgccontrol.network.GateSnapshotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.registries.ForgeRegistries;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflection bridge for Just Stargate Mod.
 *
 * v0.4.2 changes:
 * - the address database is merged from the linked gate, JSG network, and nearby-gate API;
 * - the linked/local Stargate is always present even when JSG's saved network is empty;
 * - network access prefers gate.getNetwork(), matching JSG's own runtime path.
 */
public final class JsgBridge {
    private static final int MAX_NETWORK_LINK_DISTANCE = 64;
    private static final int LOCAL_SCAN_RADIUS_XZ = 20;
    private static final int LOCAL_SCAN_RADIUS_Y = 12;
    private static final int MAX_DATABASE_TARGETS = 64;

    private static final Map<String, BlockPos> GATE_CACHE = new ConcurrentHashMap<>();
    private static final Map<String, String> MESSAGE_CACHE = new ConcurrentHashMap<>();
    private static final Set<String> OUTBOUND_ACTIVE_CACHE = ConcurrentHashMap.newKeySet();
    private static final Map<String, List<String>> OUTBOUND_SYMBOL_CACHE = new ConcurrentHashMap<>();

    private final ServerLevel level;
    private final BlockPos terminal;
    private final String terminalKey;

    private Object gate;
    private String discoveryMessage = "SCANNING JSG NETWORK";

    private record RuntimeTarget(Object address, GateSnapshotPacket.TargetInfo info) { }

    public JsgBridge(ServerLevel level, BlockPos terminal) {
        this.level = level;
        this.terminal = terminal;
        this.terminalKey = cacheKey(level, terminal);
        this.gate = findNearestGate();
    }

    public GateSnapshotPacket snapshot() {
        if (gate == null) {
            OUTBOUND_ACTIVE_CACHE.remove(terminalKey);
            OUTBOUND_SYMBOL_CACHE.remove(terminalKey);
            return new GateSnapshotPacket(false, "NO GATE", "N/A", "N/A", "N/A", "N/A",
                    0, 0, 0, false, 0, List.of(), List.of(), currentMessage());
        }

        String state = "UNKNOWN";
        String irisState = "N/A";
        String irisType = "N/A";
        String gateType = "N/A";
        String localAddress = "N/A";
        long energy = 0;
        long maxEnergy = 0;
        double angle = 0;
        boolean spinning = false;
        int dialed = 0;
        boolean idle = false;
        boolean stateIncoming = false;
        Boolean connectionInitiating = null;
        List<String> dialedAddressSymbols = List.of();

        try {
            Object dm = call(gate, "getDialingManager");
            Object stateObj = tryCall(dm, "getStargateState");
            if (stateObj != null) {
                state = String.valueOf(stateObj);
                Object idleObj = tryCall(stateObj, "idle");
                if (idleObj instanceof Boolean b) idle = b;
                Object incomingObj = tryCall(stateObj, "incoming");
                if (incomingObj instanceof Boolean b) stateIncoming = b;
            }

            Object connection = tryCall(dm, "getConnection");
            if (connection != null) {
                Object initiatingObj = tryCall(connection, "isInitiating");
                if (initiatingObj instanceof Boolean b) connectionInitiating = b;
            }

            Object spin = tryCall(dm, "getSpinHelper");
            if (spin != null) {
                angle = decimal(firstCallOrNull(spin, "getRingAngle"));
                spinning = bool(firstCallOrNull(spin, "isSpinning"));
            }

            Object dialedAddress = tryCall(dm, "getDialedAddress");
            dialed = sizeOf(dialedAddress);
            if (dialedAddress != null && dialed > 0)
                dialedAddressSymbols = List.copyOf(addressNames(dialedAddress));
        } catch (Throwable t) {
            state = "API PARTIAL";
        }

        try {
            Object iris = tryCall(gate, "getIrisManager");
            if (iris != null) {
                Object s = tryCall(iris, "getIrisState");
                Object t = tryCall(iris, "getIrisType");
                if (s != null) irisState = String.valueOf(s);
                if (t != null) irisType = String.valueOf(t);
            }
        } catch (Throwable ignored) {
        }

        try {
            Object em = tryCall(gate, "getEnergyManager");
            Object storage = em == null ? null : tryCall(em, "getStorage");
            if (storage != null) {
                energy = num(firstCallOrNull(storage, "getTrueEnergyStored", "getEnergyStored"));
                maxEnergy = num(firstCallOrNull(storage, "getTrueMaxEnergyStored", "getMaxEnergyStored", "getMaxEnergyStoredLong"));
            }
        } catch (Throwable ignored) {
        }

        try {
            Object t = tryCall(gate, "getStargateType");
            if (t != null) gateType = cleanEnum(String.valueOf(t));
        } catch (Throwable ignored) {
        }

        try {
            Object sourceSymbolType = tryCall(gate, "getSymbolType");
            Object a = sourceSymbolType == null ? null : tryCall(gate, "getStargateAddress", sourceSymbolType);
            if (a == null && sourceSymbolType != null) a = tryCall(gate, "getAddress", sourceSymbolType);
            if (a == null) a = firstCallOrNull(gate, "getStargateAddress", "getAddress");
            if (a != null) localAddress = compactAddress(a);
        } catch (Throwable ignored) {
        }

        boolean active = stateLooksActive(state);
        boolean busy = !idle || dialed > 0 || active || spinning;

        // Direction is read from JSG itself. EnumStargateState.incoming() is authoritative
        // during the incoming animation; StargateConnection.isInitiating() keeps the
        // direction authoritative after the wormhole becomes fully connected.
        boolean incoming = stateIncoming || (active && connectionInitiating != null && !connectionInitiating);

        // A dial started by this terminal is latched as outbound until the gate returns
        // to idle. This also preserves the destination if the player closes/reopens the UI.
        if (OUTBOUND_ACTIVE_CACHE.contains(terminalKey)) incoming = false;
        if (active && Boolean.TRUE.equals(connectionInitiating)) {
            OUTBOUND_ACTIVE_CACHE.add(terminalKey);
            incoming = false;
        }

        if (!busy) {
            OUTBOUND_ACTIVE_CACHE.remove(terminalKey);
            OUTBOUND_SYMBOL_CACHE.remove(terminalKey);
        }

        if (!incoming && OUTBOUND_ACTIVE_CACHE.contains(terminalKey)) {
            List<String> remembered = OUTBOUND_SYMBOL_CACHE.get(terminalKey);
            if (remembered != null && !remembered.isEmpty())
                dialedAddressSymbols = List.copyOf(remembered);
        }

        if (incoming) state = "OFFWORLD_INCOMING // " + state;

        List<GateSnapshotPacket.TargetInfo> targets = buildTargets().stream()
                .map(RuntimeTarget::info)
                .toList();

        return new GateSnapshotPacket(true, state, irisState, irisType, gateType, localAddress,
                energy, maxEnergy, angle, spinning, dialed, dialedAddressSymbols, targets, currentMessage());
    }

    public void abort() {
        if (!ensureGate()) return;
        try {
            Object dm = call(gate, "getDialingManager");
            Object r = call(dm, "abortDialingSequence");
            if (bool(r)) { OUTBOUND_ACTIVE_CACHE.remove(terminalKey); OUTBOUND_SYMBOL_CACHE.remove(terminalKey); }
            setMessage(bool(r) ? "DIAL SEQUENCE ABORTED" : "ABORT REFUSED");
        } catch (Throwable t) {
            setMessage(shortError(t));
        }
    }

    public void toggleIris() {
        if (!ensureGate()) return;
        try {
            Object iris = call(gate, "getIrisManager");
            Object r = call(iris, "toggleIris");
            setMessage(bool(r) ? "IRIS COMMAND ACCEPTED" : "IRIS BUSY / COMMAND REFUSED");
            callIf(gate, "setChanged");
        } catch (Throwable t) {
            setMessage(shortError(t));
        }
    }

    public void close() {
        if (!ensureGate()) return;
        try {
            Object dm = call(gate, "getDialingManager");
            Class<?> reason = Class.forName("dev.tauri.jsg.api.stargate.StargateClosedReasonEnum");
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object requested = Enum.valueOf((Class<? extends Enum>) reason.asSubclass(Enum.class), "REQUESTED");
            Object result = callCompatible(dm, "attemptClose", requested);
            setMessage(bool(result) || result == null ? "CLOSE COMMAND SENT" : "CLOSE COMMAND REFUSED");
        } catch (Throwable t) {
            setMessage(shortError(t));
        }
    }

    public void dialTarget(String fingerprint, int fallbackIndex) {
        if (!ensureGate()) return;
        try {
            List<RuntimeTarget> targets = buildTargets();
            RuntimeTarget target = null;

            if (fingerprint != null && !fingerprint.isBlank()) {
                for (RuntimeTarget candidate : targets) {
                    if (fingerprint.equals(targetFingerprint(candidate.info()))) {
                        target = candidate;
                        break;
                    }
                }
            }

            if (target == null && fallbackIndex >= 0 && fallbackIndex < targets.size())
                target = targets.get(fallbackIndex);

            if (target == null) {
                setMessage("INVALID TARGET");
                return;
            }

            dialRuntimeTarget(target);
        } catch (Throwable t) {
            setMessage(shortError(t));
        }
    }

    public void dialManual(String payload) {
        if (!ensureGate()) return;
        try {
            if (payload == null || payload.isBlank()) {
                setMessage("MANUAL ADDRESS EMPTY");
                return;
            }

            List<String> symbols = new ArrayList<>();
            for (String part : payload.split("\u001F", -1)) {
                if (!part.isBlank()) symbols.add(part.trim());
            }
            if (symbols.size() < 7 || symbols.size() > 9) {
                setMessage("MANUAL ADDRESS MUST CONTAIN 7-9 SYMBOLS");
                return;
            }

            Object dynamic = createDynamicAddress(symbols);
            if (dynamic == null) {
                setMessage("MANUAL ADDRESS INVALID");
                return;
            }

            GateSnapshotPacket.TargetInfo info = new GateSnapshotPacket.TargetInfo(
                    "MANUAL ADDRESS", "MANUAL", String.join(", ", symbols), "MANUAL",
                    0, 0, 0, false, true, symbols.size(), List.copyOf(symbols)
            );
            dialRuntimeTarget(new RuntimeTarget(dynamic, info));
        } catch (Throwable t) {
            setMessage(shortError(t));
        }
    }

    private void dialRuntimeTarget(RuntimeTarget target) throws Exception {
        if (!target.info().dialable()) {
            setMessage(target.info().local() ? "SELF DIAL BLOCKED" : "TARGET NOT DIALABLE FROM THIS GATE");
            return;
        }

        Object address = target.address();
        if (address == null) {
            setMessage("TARGET ADDRESS UNAVAILABLE");
            return;
        }

        Object dm = call(gate, "getDialingManager");
        Object stateObj = tryCall(dm, "getStargateState");
        if (stateObj != null) {
            Object idle = tryCall(stateObj, "idle");
            if (idle instanceof Boolean b && !b) {
                setMessage("GATE BUSY // " + cleanEnum(String.valueOf(stateObj)));
                return;
            }
        }

        Object dialedAddress = tryCall(dm, "getDialedAddress");
        if (sizeOf(dialedAddress) > 0) {
            setMessage("ADDRESS BUFFER NOT EMPTY // ABORT FIRST");
            return;
        }

        Class<?> dialingType = Class.forName("dev.tauri.jsg.api.stargate.animation.EnumDialingType");
        @SuppressWarnings({"rawtypes", "unchecked"})
        Object normal = Enum.valueOf((Class<? extends Enum>) dialingType.asSubclass(Enum.class), "NORMAL");

        callCompatible(dm, "dialAddress", address, false, false, normal);
        markOutboundDial(target);
        setMessage("DIALING // " + target.info().name());
    }

    private void markOutboundDial(RuntimeTarget target) {
        OUTBOUND_ACTIVE_CACHE.add(terminalKey);
        List<String> symbols = target == null || target.info() == null || target.info().symbols() == null
                ? List.of() : target.info().symbols();
        if (!symbols.isEmpty()) OUTBOUND_SYMBOL_CACHE.put(terminalKey, List.copyOf(symbols));
    }

    private static boolean stateLooksActive(String state) {
        String s = state == null ? "" : state.toLowerCase(Locale.ROOT);
        return s.contains("engag") || s.contains("connect") || s.contains("open") || s.contains("established");
    }

    private static String targetFingerprint(GateSnapshotPacket.TargetInfo t) {
        if (t == null) return "";
        return t.dimension() + "|" + t.x() + "|" + t.y() + "|" + t.z() + "|" + t.address() + "|" + t.name();
    }

    private List<RuntimeTarget> buildTargets() {
        if (gate == null) return List.of();

        LinkedHashMap<String, RuntimeTarget> merged = new LinkedHashMap<>();

        RuntimeTarget local = buildLocalTarget();
        if (local != null) merged.put(targetKey(local), local);

        for (RuntimeTarget target : buildTargetsFromNetwork())
            merged.putIfAbsent(targetKey(target), target);

        // Also merge JSG's nearby-gate API. This is useful on worlds where the saved
        // StargateNetwork has not yet been populated but JSG can still resolve routes.
        for (RuntimeTarget target : buildTargetsFromNearby())
            merged.putIfAbsent(targetKey(target), target);

        return new ArrayList<>(merged.values());
    }

    private String targetKey(RuntimeTarget target) {
        if (target == null || target.info() == null) return UUID.randomUUID().toString();
        GateSnapshotPacket.TargetInfo i = target.info();
        if (i.local()) return "LOCAL";
        return i.dimension() + "|" + i.x() + "|" + i.y() + "|" + i.z() + "|" + i.address();
    }

    private RuntimeTarget buildLocalTarget() {
        try {
            Object sourceSymbolType = tryCall(gate, "getSymbolType");
            Object address = sourceSymbolType == null ? null : tryCall(gate, "getStargateAddress", sourceSymbolType);
            if (address == null && sourceSymbolType != null) address = tryCall(gate, "getAddress", sourceSymbolType);
            if (address == null) return null;

            BlockPos pos = gate instanceof BlockEntity be ? be.getBlockPos() : terminal;
            Object stargatePos = tryCall(gate, "getStargatePos");
            String name = stargatePos == null ? "" : stringOrEmpty(tryCall(stargatePos, "getName"));
            if (name.isBlank()) name = "LOCAL STARGATE";

            Object dimensionObj = stargatePos == null ? level.dimension()
                    : readFieldOrGetter(stargatePos, "dimension", "getDimension");
            String dimension = dimensionLabel(dimensionObj == null ? level.dimension() : dimensionObj);

            Object typeObj = tryCall(gate, "getStargateType");
            String type = typeObj == null ? "GATE" : cleanEnum(String.valueOf(typeObj));
            List<String> symbols = addressNames(address);

            GateSnapshotPacket.TargetInfo info = new GateSnapshotPacket.TargetInfo(
                    name, dimension, compactAddress(address), type,
                    pos.getX(), pos.getY(), pos.getZ(),
                    true, false, 0, List.copyOf(symbols)
            );
            return new RuntimeTarget(null, info);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private List<RuntimeTarget> buildTargetsFromNetwork() {
        try {
            Object network = tryCall(gate, "getNetwork");
            if (network == null) {
                Class<?> networkClass = Class.forName("dev.tauri.jsg.common.stargate.network.StargateNetwork");
                network = networkClass.getField("INSTANCE").get(null);
            }
            Object allObj = call(network, "getAll");
            if (!(allObj instanceof Map<?, ?> all) || all.isEmpty()) return List.of();

            Object sourceSymbolType = tryCall(gate, "getSymbolType");
            BlockPos sourcePos = gate instanceof BlockEntity be ? be.getBlockPos() : null;
            List<RuntimeTarget> out = new ArrayList<>();

            for (Map.Entry<?, ?> entry : all.entrySet()) {
                Object stargatePos = entry.getKey();
                Object addressMapObj = entry.getValue();

                Object dimObj = readFieldOrGetter(stargatePos, "dimension", "getDimension");
                Object posObj = readFieldOrGetter(stargatePos, "gatePos", "getGatePos");
                if (!(posObj instanceof BlockPos pos)) continue;
                if (bool(readFieldOrGetter(stargatePos, "blacklisted", "isBlacklisted"))) continue;

                boolean local = sourcePos != null && sameDimension(dimObj, level.dimension()) && sourcePos.equals(pos);
                Object sourceAddress = chooseAddress(addressMapObj, sourceSymbolType);
                Object displayAddress = sourceAddress != null ? sourceAddress : firstAddress(addressMapObj);
                if (displayAddress == null || sizeOfAddress(displayAddress) < 6) continue;

                String name = stringOrEmpty(tryCall(stargatePos, "getName"));
                String dimension = dimensionLabel(dimObj);
                String type = "GATE";
                Object typeObj = firstCallOrNull(stargatePos, "getStargateType");
                if (typeObj == null) typeObj = readField(stargatePos, "stargateType");
                if (typeObj != null) type = cleanEnum(String.valueOf(typeObj));
                if (name.isBlank()) name = local ? "LOCAL STARGATE" : autoName(dimension, pos, out.size() + 1);

                List<String> baseSymbols = addressNames(displayAddress);
                int symbolsNeeded = local ? 0 : getSymbolsNeededForTarget(stargatePos, dimObj, type);
                List<String> displaySymbols = local ? baseSymbols : buildDialSymbols(baseSymbols, symbolsNeeded);

                boolean dialable = !local && sourceAddress != null;
                Object dialAddress = null;
                if (dialable) {
                    List<String> sourceNames = addressNames(sourceAddress);
                    List<String> dialSymbols = buildDialSymbols(sourceNames, symbolsNeeded);
                    dialAddress = createDynamicAddress(dialSymbols);
                    if (dialAddress == null) dialAddress = sourceAddress;
                    displaySymbols = dialSymbols;
                }

                GateSnapshotPacket.TargetInfo info = new GateSnapshotPacket.TargetInfo(
                        name,
                        dimension,
                        compactAddress(displayAddress),
                        type,
                        pos.getX(), pos.getY(), pos.getZ(),
                        local,
                        dialable,
                        symbolsNeeded,
                        List.copyOf(displaySymbols)
                );
                out.add(new RuntimeTarget(dialAddress, info));
                if (out.size() >= MAX_DATABASE_TARGETS) break;
            }

            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private List<RuntimeTarget> buildTargetsFromNearby() {
        try {
            List<?> list = asList(call(gate, "getNearbyGates"));
            List<RuntimeTarget> out = new ArrayList<>();
            int i = 1;
            for (Object g : list) {
                Object address = readMember(g, "address", "getAddress");
                if (address == null) continue;
                Object type = readMember(g, "gateType", "getGateType");
                int symbolsNeeded = intValue(readMember(g, "symbolsNeeded", "getSymbolsNeeded"), 7);
                List<String> symbols = addressNames(address);
                GateSnapshotPacket.TargetInfo info = new GateSnapshotPacket.TargetInfo(
                        "NEARBY GATE " + i,
                        "LOCAL NETWORK",
                        compactAddress(address),
                        type == null ? "GATE" : cleanEnum(String.valueOf(type)),
                        0, 0, 0,
                        false,
                        true,
                        symbolsNeeded,
                        List.copyOf(symbols)
                );
                out.add(new RuntimeTarget(address, info));
                i++;
            }
            return out;
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static Object firstAddress(Object addressMapObj) {
        if (!(addressMapObj instanceof Map<?, ?> map)) return null;
        for (Object value : map.values()) if (value != null) return value;
        return null;
    }

    private List<String> addressNames(Object address) {
        if (address == null) return List.of();
        Object list = tryCall(address, "getNameList");
        if (list instanceof Collection<?> c) {
            List<String> out = new ArrayList<>();
            for (Object value : c) if (value != null) out.add(String.valueOf(value));
            if (!out.isEmpty()) return out;
        }

        int size = sizeOfAddress(address);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Object symbol = tryCall(address, "get", i);
            if (symbol == null) continue;
            Object name = tryCall(symbol, "getEnglishName");
            out.add(name == null ? String.valueOf(symbol) : String.valueOf(name));
        }
        return out;
    }

    private static List<String> buildDialSymbols(List<String> base, int symbolsNeeded) {
        if (base == null || base.isEmpty()) return List.of();
        int needed = Math.max(7, Math.min(9, symbolsNeeded <= 0 ? 7 : symbolsNeeded));
        List<String> out = new ArrayList<>(needed);
        int addressGlyphs = Math.min(base.size(), needed - 1);
        for (int i = 0; i < addressGlyphs; i++) out.add(base.get(i));
        out.add("Point of Origin");
        return out;
    }

    private int getSymbolsNeededForTarget(Object stargatePos, Object targetDimension, String type) {
        try {
            Object dm = tryCall(gate, "getDialingManager");
            Object targetSymbolType = tryCall(stargatePos, "getGateSymbolType");
            if (targetSymbolType == null) targetSymbolType = readField(stargatePos, "gateSymbolType");
            if (dm != null && targetSymbolType != null) {
                Object needed = callCompatible(dm, "getMinimalSymbolsToDial", targetSymbolType, stargatePos);
                if (needed instanceof Number n) {
                    int value = n.intValue();
                    if (value >= 7 && value <= 9) return value;
                }
            }
        } catch (Throwable ignored) {
        }
        return guessSymbolsNeeded(targetDimension, level.dimension(), type);
    }

    private static int guessSymbolsNeeded(Object targetDimension, ResourceKey<Level> current, String type) {
        if (sameDimension(targetDimension, current)) return 7;
        String t = type == null ? "" : type.toUpperCase(Locale.ROOT);
        if (t.contains("UNIVERSE")) return 9;
        return 8;
    }

    private Object createDynamicAddress(List<String> symbols) {
        if (symbols == null || symbols.size() < 7) return null;
        try {
            Object sourceSymbolType = tryCall(gate, "getSymbolType");
            if (sourceSymbolType == null) return null;
            Class<?> dynamicClass = Class.forName("dev.tauri.jsg.api.stargate.network.address.StargateAddressDynamic");
            Object dynamic = null;
            for (var ctor : dynamicClass.getConstructors()) {
                if (ctor.getParameterCount() != 1) continue;
                Class<?> parameter = ctor.getParameterTypes()[0];
                if (parameter.isInstance(sourceSymbolType) || parameter.isAssignableFrom(sourceSymbolType.getClass())) {
                    dynamic = ctor.newInstance(sourceSymbolType);
                    break;
                }
            }
            if (dynamic == null) return null;

            for (String name : symbols) {
                Object symbol = callCompatible(gate, "getSymbolFromNameIndex", name);
                callCompatible(dynamic, "addSymbol", symbol);
            }
            return dynamic;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static int intValue(Object value, int fallback) {
        return value instanceof Number n ? n.intValue() : fallback;
    }

    private static Object chooseAddress(Object addressMapObj, Object sourceSymbolType) {
        if (!(addressMapObj instanceof Map<?, ?> addressMap) || addressMap.isEmpty()) return null;

        if (sourceSymbolType != null) {
            Object direct = addressMap.get(sourceSymbolType);
            if (direct != null) return direct;

            String sourceId = symbolTypeId(sourceSymbolType);
            for (Map.Entry<?, ?> e : addressMap.entrySet()) {
                if (sourceId.equals(symbolTypeId(e.getKey()))) return e.getValue();
            }

            // A target without an address for the source gate's symbol system is not dialable
            // from this terminal, so do not advertise it as a valid destination.
            return null;
        }

        // Very old/partial JSG builds may not expose the source symbol type.
        for (Object value : addressMap.values()) if (value != null) return value;
        return null;
    }

    private static String symbolTypeId(Object type) {
        if (type == null) return "";
        Object id = tryCall(type, "getId");
        return id == null ? String.valueOf(type) : String.valueOf(id);
    }

    private static int sizeOfAddress(Object address) {
        if (address == null) return 0;
        Object size = firstCallOrNull(address, "getSize", "size");
        if (size instanceof Number n) return n.intValue();
        return 7; // Some old JSG address implementations do not expose size through reflection.
    }

    private static String compactAddress(Object address) {
        if (address == null) return "N/A";
        String s = String.valueOf(address)
                .replace("StargateAddress", "")
                .replace("StargateAddressDynamic", "")
                .replace('\n', ' ')
                .replace('\r', ' ')
                .trim();
        while (s.contains("  ")) s = s.replace("  ", " ");
        if (s.length() > 90) s = s.substring(0, 89) + "…";
        return s.isBlank() ? "ADDRESS AVAILABLE" : s;
    }

    private static String dimensionLabel(Object dimension) {
        if (dimension == null) return "UNKNOWN DIMENSION";
        Object location = tryCall(dimension, "location");
        String raw = location == null ? String.valueOf(dimension) : String.valueOf(location);
        int colon = raw.indexOf(':');
        String path = colon >= 0 ? raw.substring(colon + 1) : raw;
        return path.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static String autoName(String dimension, BlockPos pos, int index) {
        if (dimension != null && !dimension.isBlank()) {
            return dimension + " // " + pos.getX() + " " + pos.getZ();
        }
        return String.format(Locale.ROOT, "STARGATE %02d", index);
    }

    private static String stringOrEmpty(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean ensureGate() {
        if (gate != null) return true;
        gate = findNearestGate();
        if (gate == null) {
            setMessage("NO JSG STARGATE LINKED");
            return false;
        }
        return true;
    }

    private Object findNearestGate() {
        BlockPos cached = GATE_CACHE.get(terminalKey);
        if (cached != null) {
            BlockEntity cachedBe = level.getBlockEntity(cached);
            if (cachedBe != null && isJsgGateBase(cachedBe)) {
                discoveryMessage = linkMessage("CACHE", cachedBe);
                return cachedBe;
            }
            GATE_CACHE.remove(terminalKey);
        }

        Object fromNetwork = findViaJsgNetwork();
        if (fromNetwork != null) return fromNetwork;

        Object fromScan = findViaLocalScan();
        if (fromScan != null) return fromScan;

        discoveryMessage = "NO REGISTERED JSG GATE WITHIN " + MAX_NETWORK_LINK_DISTANCE + " BLOCKS";
        MESSAGE_CACHE.put(terminalKey, discoveryMessage);
        return null;
    }

    private Object findViaJsgNetwork() {
        try {
            Class<?> networkClass = Class.forName("dev.tauri.jsg.common.stargate.network.StargateNetwork");
            Field instanceField = networkClass.getField("INSTANCE");
            Object network = instanceField.get(null);
            Object allObj = call(network, "getAll");
            if (!(allObj instanceof Map<?, ?> all) || all.isEmpty()) return null;

            double maxSq = (double) MAX_NETWORK_LINK_DISTANCE * MAX_NETWORK_LINK_DISTANCE;
            double bestSq = Double.MAX_VALUE;
            BlockPos bestPos = null;

            for (Object stargatePos : all.keySet()) {
                Object dimObj = readFieldOrGetter(stargatePos, "dimension", "getDimension");
                Object posObj = readFieldOrGetter(stargatePos, "gatePos", "getGatePos");
                if (!(posObj instanceof BlockPos pos)) continue;
                if (!sameDimension(dimObj, level.dimension())) continue;

                double d = distSq(pos, terminal);
                if (d <= maxSq && d < bestSq) {
                    bestSq = d;
                    bestPos = pos;
                }
            }

            if (bestPos != null) {
                BlockEntity be = level.getBlockEntity(bestPos);
                if (be != null && isJsgGateBase(be)) {
                    GATE_CACHE.put(terminalKey, bestPos.immutable());
                    discoveryMessage = linkMessage("JSG NETWORK", be);
                    MESSAGE_CACHE.put(terminalKey, discoveryMessage);
                    return be;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private Object findViaLocalScan() {
        double best = Double.MAX_VALUE;
        BlockEntity bestGate = null;

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dy = -LOCAL_SCAN_RADIUS_Y; dy <= LOCAL_SCAN_RADIUS_Y; dy++) {
            for (int dx = -LOCAL_SCAN_RADIUS_XZ; dx <= LOCAL_SCAN_RADIUS_XZ; dx++) {
                for (int dz = -LOCAL_SCAN_RADIUS_XZ; dz <= LOCAL_SCAN_RADIUS_XZ; dz++) {
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d >= best) continue;

                    p.set(terminal.getX() + dx, terminal.getY() + dy, terminal.getZ() + dz);
                    if (!level.hasChunkAt(p)) continue;
                    BlockEntity be = level.getBlockEntity(p);
                    if (be != null && isJsgGateBase(be)) {
                        best = d;
                        bestGate = be;
                    }
                }
            }
        }

        if (bestGate != null) {
            BlockPos pos = bestGate.getBlockPos().immutable();
            GATE_CACHE.put(terminalKey, pos);
            discoveryMessage = linkMessage("LOCAL SCAN", bestGate);
            MESSAGE_CACHE.put(terminalKey, discoveryMessage);
        }
        return bestGate;
    }

    private static boolean isJsgGateBase(BlockEntity be) {
        try {
            ResourceLocation id = ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock());
            if (id != null && id.getNamespace().equals("jsg")) {
                String path = id.getPath().toLowerCase(Locale.ROOT);
                if (path.startsWith("stargate_") && path.contains("base")) return true;
            }
        } catch (Throwable ignored) {
        }

        String className = be.getClass().getName().toLowerCase(Locale.ROOT);
        if (className.contains("jsg") && className.contains("stargate") && className.contains("base")) {
            return hasMethod(be.getClass(), "getDialingManager") && hasMethod(be.getClass(), "getEnergyManager");
        }

        return hasMethod(be.getClass(), "getDialingManager")
                && hasMethod(be.getClass(), "getStargateType")
                && hasMethod(be.getClass(), "getSymbolType");
    }

    private String linkMessage(String source, BlockEntity be) {
        ResourceLocation id = null;
        try {
            id = ForgeRegistries.BLOCKS.getKey(be.getBlockState().getBlock());
        } catch (Throwable ignored) {
        }
        BlockPos p = be.getBlockPos();
        return "LINKED VIA " + source + " // " + (id == null ? be.getClass().getSimpleName() : id) +
                " @ " + p.getX() + " " + p.getY() + " " + p.getZ();
    }

    private String currentMessage() {
        return MESSAGE_CACHE.getOrDefault(terminalKey, discoveryMessage);
    }

    private void setMessage(String message) {
        MESSAGE_CACHE.put(terminalKey, message == null ? "" : message);
    }

    private static String cacheKey(ServerLevel level, BlockPos pos) {
        return level.dimension().location() + ":" + pos.asLong();
    }

    private static boolean sameDimension(Object candidate, ResourceKey<Level> current) {
        if (candidate == null) return false;
        if (candidate.equals(current)) return true;
        try {
            Object location = call(candidate, "location");
            return location != null && location.toString().equals(current.location().toString());
        } catch (Throwable ignored) {
            return candidate.toString().contains(current.location().toString());
        }
    }

    private static double distSq(BlockPos a, BlockPos b) {
        double dx = a.getX() - b.getX();
        double dy = a.getY() - b.getY();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean hasMethod(Class<?> type, String name) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return true;
            }
        }
        for (Method m : type.getMethods()) if (m.getName().equals(name)) return true;
        return false;
    }

    private static Object call(Object target, String name, Object... args) throws Exception {
        return callCompatible(target, name, args);
    }

    private static Object tryCall(Object target, String name, Object... args) {
        if (target == null) return null;
        try {
            return callCompatible(target, name, args);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object callCompatible(Object target, String name, Object... args) throws Exception {
        if (target == null) throw new NullPointerException("target");
        Method candidate = null;

        for (Method m : target.getClass().getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            if (compatible(m.getParameterTypes(), args)) {
                candidate = m;
                break;
            }
        }
        if (candidate == null) {
            for (Class<?> c = target.getClass(); c != null && candidate == null; c = c.getSuperclass()) {
                for (Method m : c.getDeclaredMethods()) {
                    if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                    if (compatible(m.getParameterTypes(), args)) {
                        candidate = m;
                        break;
                    }
                }
            }
        }

        if (candidate == null)
            throw new NoSuchMethodException(target.getClass().getName() + "." + name + "/" + args.length);

        if (!Modifier.isPublic(candidate.getModifiers()) || !candidate.canAccess(target)) candidate.setAccessible(true);
        return candidate.invoke(target, args);
    }

    private static boolean compatible(Class<?>[] types, Object[] args) {
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) continue;
            Class<?> expected = types[i].isPrimitive() ? wrap(types[i]) : types[i];
            if (!expected.isAssignableFrom(args[i].getClass())) return false;
        }
        return true;
    }

    private static Class<?> wrap(Class<?> c) {
        if (c == boolean.class) return Boolean.class;
        if (c == int.class) return Integer.class;
        if (c == long.class) return Long.class;
        if (c == double.class) return Double.class;
        if (c == float.class) return Float.class;
        if (c == short.class) return Short.class;
        if (c == byte.class) return Byte.class;
        if (c == char.class) return Character.class;
        return c;
    }

    private static Object firstCallOrNull(Object target, String... names) {
        for (String name : names) {
            Object value = tryCall(target, name);
            if (value != null) return value;
        }
        return null;
    }

    private static void callIf(Object target, String name) {
        try {
            call(target, name);
        } catch (Throwable ignored) {
        }
    }

    private static Object readMember(Object target, String field, String getter) {
        if (target == null) return null;
        Object viaGetter = tryCall(target, getter);
        if (viaGetter != null) return viaGetter;
        return readField(target, field);
    }

    private static Object readFieldOrGetter(Object target, String field, String getter) {
        if (target == null) return null;
        Object value = readField(target, field);
        if (value != null) return value;
        return tryCall(target, getter);
    }

    private static Object readField(Object target, String field) {
        if (target == null) return null;
        try {
            Field f = target.getClass().getField(field);
            f.setAccessible(true);
            return f.get(target);
        } catch (Throwable ignored) {
        }
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(field);
                f.setAccessible(true);
                return f.get(target);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static List<?> asList(Object o) {
        if (o instanceof List<?> l) return l;
        if (o instanceof Collection<?> c) return new ArrayList<>(c);
        return List.of();
    }

    private static int sizeOf(Object o) {
        if (o instanceof Collection<?> c) return c.size();
        if (o == null) return 0;
        Object v = tryCall(o, "size");
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static long num(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }

    private static double decimal(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static boolean bool(Object o) {
        return o instanceof Boolean b && b;
    }

    private static String cleanEnum(String raw) {
        if (raw == null) return "?";
        String value = raw;
        int at = value.lastIndexOf(':');
        if (at >= 0 && at + 1 < value.length()) value = value.substring(at + 1);
        return value.replace('_', ' ').toUpperCase(Locale.ROOT);
    }

    private static String shortError(Throwable t) {
        Throwable x = t;
        while (x.getCause() != null) x = x.getCause();
        String s = x.getClass().getSimpleName() + ": " + String.valueOf(x.getMessage());
        return s.length() > 110 ? s.substring(0, 110) : s;
    }
}
