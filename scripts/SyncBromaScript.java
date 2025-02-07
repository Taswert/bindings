// Sync your RE'd addresses to & from GeometryDash.bro 
// @author HJfod
// @category GeodeSDK

import ghidra.app.script.GhidraScript;
import ghidra.features.base.values.GhidraValuesMap;
import ghidra.program.model.data.AbstractFloatDataType;
import ghidra.program.model.data.CategoryPath;
import ghidra.program.model.data.DataType;
import ghidra.program.model.data.DataTypeConflictHandler;
import ghidra.program.model.data.DataTypePath;
import ghidra.program.model.data.DoubleDataType;
import ghidra.program.model.data.EnumDataType;
import ghidra.program.model.data.FloatDataType;
import ghidra.program.model.data.IntegerDataType;
import ghidra.program.model.data.PointerDataType;
import ghidra.program.model.data.StructureDataType;
import ghidra.program.model.listing.AutoParameterImpl;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.GhidraClass;
import ghidra.program.model.listing.ParameterImpl;
import ghidra.program.model.listing.ReturnParameterImpl;
import ghidra.program.model.listing.Variable;
import ghidra.program.model.listing.VariableStorage;
import ghidra.program.model.listing.Function.FunctionUpdateType;
import ghidra.program.model.symbol.Namespace;
import ghidra.program.model.symbol.SourceType;

import java.io.File;
import java.nio.file.Files;
import java.text.MessageFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

// https://www.baeldung.com/java-lambda-exceptions
@FunctionalInterface
interface ThrowingConsumer<T, E extends Exception> {
    void accept(T t) throws E;
}

class Regexes {
    static final Pattern GRAB_NAMED_GROUP = Pattern.compile("(?<=\\(\\?)<\\w+>", 0);
    
    static final<T> String removeNamedGroups(T pattern) {
        return GRAB_NAMED_GROUP.matcher(pattern.toString()).replaceAll(":");
    }
    static final<T> String formatRegex(String fmt, T... args) {
        return MessageFormat.format(
            fmt,
            Arrays.asList(args).stream().map(p -> removeNamedGroups(p)).toArray()
        );
    }
    static final Pattern generateRecursiveRegex(String format, int depth, String basecase, int flags) {
        var result = MessageFormat.format(format, basecase);
        while (depth > 0) {
            result = MessageFormat.format(format, removeNamedGroups(result));
            depth -= 1;
        }
        return Pattern.compile(result, flags);
    }

    public static final Pattern GRAB_LINK_ATTR = Pattern.compile(
        "link\\((?<platforms>.*?)\\)",
        Pattern.DOTALL
    );
    public static final Pattern GRAB_CLASSES = Pattern.compile(
        // Grab attributes
        "(?<attrs>\\[\\[.*?\\]\\]\\s*)?" + 
        // Grab name
        "class (?<name>(?:\\w+::)*\\w+)\\s+(?::.*?)?" + 
        // Grab body (assuming closing brace is on its own line without any preceding whitespace)
        "\\{(?<body>.*?)^\\}",
        Pattern.DOTALL | Pattern.MULTILINE
    );
    public static final Pattern GRAB_TYPE = generateRecursiveRegex(
        "(?<lconst>\\bconst\\s+)?(?<name>(?:\\w+::)*\\w+)(?<template><(?:{0})(?:\\s*,\\s*(?:{0}))*>)?(?<rconst>\\s+const\\b)?(?<ptr>\\s*\\*+)?(?<ref>\\s*&+)?",
        2,
        "__depth_limit",
        Pattern.DOTALL
    );
    public static final Pattern GRAB_PARAM = Pattern.compile(
        formatRegex(
            "(?<type>{0})(?:\\s+(?<name>\\w+))?",
            GRAB_TYPE
        ),
        Pattern.DOTALL
    );
    public static final Pattern GRAB_FUNCTION = Pattern.compile(
        formatRegex(
            // Must match start of line (MULTILINE flag required) - also requires that the 
            // function not be intended more than 4 spaces or a single tab
            // "(?<=^(?:(?: {0,4})|\\t))" + 
            // Get the dispatch modifier keyword if one is defined
            "(?<dispatch>(?:inline|virtual|static|callback)\\s+)?" +
            // Grab the return type and name of the function, or the name if it's a destructor
            "(?:(?:(?<return>{0})\\s+(?<name>\\w+))|(?<destructor>~\\w+))" + 
            // Grab the parameters
            "\\(\\s*(?<params>(?:{1},?)*)\\)" +
            // Grab the platforms
            "(?:\\s*=\\s*(?<platforms>(?:[a-z]+\\s+0x[0-9a-fA-F]+\\s*,?\\s*)+))?",
            GRAB_TYPE, GRAB_PARAM
        ),
        Pattern.DOTALL | Pattern.MULTILINE
    );
    public static final Pattern GRAB_WIN_ADDRESS = Pattern.compile(
        "win\\s+0x(?<addr>[0-9a-fA-F]+)",
        Pattern.DOTALL
    );
    public static final Pattern GRAB_MAC_ADDRESS = Pattern.compile(
        "mac\\s+0x(?<addr>[0-9a-fA-F]+)",
        Pattern.DOTALL
    );
}

enum CConv {
    CDECL,
    THISCALL,
    MEMBERCALL,
    FASTCALL,
    OPTCALL,
}

public class SyncBromaScript extends GhidraScript {
    int importedAddCount = 0;
    int importedUpdateCount = 0;

    public void run() throws Exception {
        // Get the bindings directory from the location of this script
        // todo: maybe ask the user for this if the script is not in the expected place?
        var bindingsDir = new File(this.sourceFile.getParentFile().getParentFile().toString() + "/bindings");
        if (!bindingsDir.isDirectory()) {
            throw new Error("SyncBromaScript should be located in <Geode bindings>/scripts!");
        }
        printfmt("Bindings directory: {0}", bindingsDir.toPath().toString());

        // Get all available bindings versions from the bindings directory
        List<File> versions = new ArrayList<File>();
        for (var file : bindingsDir.listFiles()) {
            if (file.isDirectory()) {
                versions.add(file);
            }
        }
        var targetBromas = List.of("Cocos2d.bro", "GeometryDash.bro");

        // Get the target platform and version from the user
        var map = new GhidraValuesMap();
        map.defineChoice("Target platform", null, getPlatformOptions().toArray(String[]::new));
        map.defineChoice("Broma file (Windows-only)", null, targetBromas.toArray(String[]::new));
        map.defineChoice(
            "Game version",
            versions.get(versions.size() - 1).getName().toString(),
            versions.stream().map(e -> e.getName().toString()).toArray(String[]::new)
        );
        askValues(
            "Sync Broma",
            "Import addresses & signatures from Broma, and add new ones " + 
            "from the current project to it",
            map
        );
        var platform = map.getChoice("Target platform");
        var version = map.getChoice("Game version");
        if (platform == "Windows") {
            targetBromas = List.of(map.getChoice("Broma file (Windows-only)"));
        }
        var platformAddrGrab = getPlatformAddrPattern(platform);

        printfmt("Loading addresses from Bindings...");
        var bindingsVerDir = new File(bindingsDir.toPath().toString() + "/" + version);
        var listing = currentProgram.getListing();

        // Read the broma files and merge function addresses & their signatures into Ghidra
        for (var bro : targetBromas) {
            var file = new File(bindingsVerDir.toPath().toString() + "/" + bro);
            printfmt("Reading {0}...", bro);
            matchAll(
                Regexes.GRAB_CLASSES,
                new String(Files.readAllBytes(file.toPath())),
                cls -> {
                    var linkValue = false;
                    var attrs = cls.group("attrs");
                    if (attrs != null) {
                        var attr = Regexes.GRAB_LINK_ATTR.matcher(attrs);
                        if (attr.find()) {
                            if (attr.group("platforms").contains(getPlatformLinkName(platform))) {
                                linkValue = true;
                            }
                        }
                    }
                    final var link = linkValue;
                    matchAll(
                        Regexes.GRAB_FUNCTION, 
                        cls.group("body"),
                        fun -> {
                            // Get function name either from destructor or custom name
                            var name = fun.group("destructor");
                            if (name == null) {
                                name = fun.group("name");
                            }
                            final var fullName = cls.group("name") + "::" + name;

                            // Get the address of this function on the platform, 
                            // or if it's not defined, then skip this function 
                            // (since there's nothing to import)
                            var platforms = fun.group("platforms");
                            if (platforms == null) {
                                return;
                            }
                            var plat = platformAddrGrab.matcher(platforms);
                            if (!plat.find()) {
                                return;
                            }
                            var offset = Integer.parseInt(plat.group("addr"), 16);
                            // The hardcoded placeholder addr
                            if (offset == 0x9999999) {
                                return;
                            }
                            var addr = currentProgram.getImageBase().add(offset);

                            var didUpdateThis = false;
                            var didAddThis = false;

                            // Get the function defined at the address, or 
                            var data = listing.getFunctionAt(addr);
                            if (data == null) {
                                didAddThis = true;
                                data = createFunction(addr, name);
                                if (data == null) {
                                    throw new Error("Unable to create a function at address " + addr.toString());
                                }
                                data.setParentNamespace(parseNamespace(cls.group("name")));
                            }

                            // Get the calling convention
                            final var conv = getCallingConvention(platform, link, fun);
                            
                            // Parse return type, or null if this is a destructor
                            ReturnParameterImpl bromaRetType = null;
                            var retTypeStr = fun.group("return");
                            if (retTypeStr != null) {
                                bromaRetType = new ReturnParameterImpl(
                                    parseType(retTypeStr),
                                    currentProgram
                                );
                            }

                            // Parse args
                            var collectBromaParams = new ArrayList<Variable>();

                            // Add `this` arg
                            final var dispatch = fun.group("dispatch");
                            if (dispatch == null || !dispatch.equals("static")) {
                                collectBromaParams.add(new ParameterImpl(
                                    "this",
                                    parseType(cls.group("name") + "*"),
                                    currentProgram
                                ));
                            }

                            printfmt("ret: {0}", bromaRetType);

                            // Struct return
                            if (bromaRetType != null && bromaRetType.getDataType() instanceof StructureDataType) {
                                collectBromaParams.add(new ParameterImpl(
                                    "ret",
                                    bromaRetType.getDataType(),
                                    currentProgram
                                ));
                            }
                            
                            matchAll(
                                Regexes.GRAB_PARAM,
                                fun.group("params"),
                                param -> {
                                    collectBromaParams.add(new ParameterImpl(
                                        param.group("name"),
                                        parseType(param.group("type")),
                                        currentProgram
                                    ));
                                }
                            );
                            // Have to assign this outside the closure because otherwise Java
                            // complains about effective finality...
                            var bromaParams = collectBromaParams;

                            // Ask for mismatches between the incoming signature

                            var signatureConflict = false;

                            // If the Ghidra function has more parameters than Broma, 
                            // then ask for whole signature override
                            if (data.getParameterCount() > bromaParams.size()) {
                                signatureConflict = true;
                            }
                            else {
                                for (var i = 0; i < data.getParameterCount(); i += 1) {
                                    var param = data.getParameter(i);
                                    var bromaParam = bromaParams.get(i);
                                    // Only care about mismatches against user-defined types
                                    if (param.getSource() == SourceType.USER_DEFINED) {
                                        if (
                                            !param.getDataType().isEquivalent(bromaParam.getDataType()) ||
                                            (
                                                param.getName() != null && bromaParam.getName() != null &&
                                                !param.getName().equals(bromaParam.getName())
                                            )
                                        ) {
                                            signatureConflict = true;
                                        }
                                    }
                                }
                            }
                            // Destructor signatures are weird
                            if (fun.group("destructor") != null) {
                                signatureConflict = false;
                            }
                            if (signatureConflict) {
                                if (!askBromaConflict(
                                    fullName, "signature",
                                    "(" + String.join(", ", bromaParams
                                        .stream()
                                        .map(p -> p.getDataType().toString() + " " + p.getName())
                                        .toArray(String[]::new)
                                    ) + ")",
                                    "(" + String.join(", ", Arrays.asList(data.getParameters())
                                        .stream()
                                        .map(p -> p.getDataType() + " " + p.getName())
                                        .toArray(String[]::new)
                                    ) + ")"
                                )) {
                                    bromaParams = new ArrayList<Variable>(Arrays.asList(data.getParameters()));
                                    didUpdateThis = true;
                                }
                            }
                            if (data.getReturn().getSource() == SourceType.USER_DEFINED && bromaRetType != null) {
                                if (!data.getReturnType().isEquivalent(bromaRetType.getDataType())) {
                                    if (!askBromaConflict(
                                        fullName, "return type",
                                        bromaRetType.getDataType(), data.getReturnType()
                                    )) {
                                        bromaRetType = null;
                                    }
                                    else {
                                        didUpdateThis = true;
                                    }
                                }
                            }

                            FunctionUpdateType updateType;
                            // Manual storage for custom calling conventions
                            if (
                                (conv == CConv.MEMBERCALL || conv == CConv.OPTCALL) && 
                                // Only do manual storage if there's actually a need for it
                                bromaParams.stream().anyMatch(p ->
                                    p.getDataType() instanceof StructureDataType ||
                                    p.getDataType() instanceof FloatDataType
                                )
                            ) {
                                updateType = FunctionUpdateType.CUSTOM_STORAGE;
                                var reorderedParams = new ArrayList<Variable>(bromaParams);
                                // Thanks stable sort <3
                                reorderedParams.sort((a, b) -> {
                                    final var aIs = a.getDataType() instanceof StructureDataType;
                                    final var bIs = b.getDataType() instanceof StructureDataType;
                                    if (aIs && bIs) return 0;
                                    if (aIs) return 1;
                                    if (bIs) return -1;
                                    return 0;
                                });
                                var stackOffset = 0;
                                for (var i = 0; i < bromaParams.size(); i += 1) {
                                    var param = bromaParams.get(i);
                                    final var type = param.getDataType();
                                    VariableStorage storage;
                                    if (i < 5 && type instanceof AbstractFloatDataType) {
                                        // (p)rocessor (reg)ister
                                        String preg = null;
                                        if (type instanceof FloatDataType) {
                                            preg = "XMM" + i + "_Da";
                                        }
                                        else if (type instanceof DoubleDataType) {
                                            preg = "XMM" + i + "_Qa";
                                        }
                                        else {
                                            throw new Error(
                                                "Parameter has type " + type.toString() +
                                                ", which is floating-point type but has an unknown register location"
                                            );
                                        }
                                        storage = new VariableStorage(currentProgram, currentProgram.getRegister(preg));
                                    }
                                    else {
                                        if (i == 0) {
                                            storage = new VariableStorage(currentProgram, currentProgram.getRegister("ECX"));
                                        }
                                        else if (conv == CConv.OPTCALL && i == 1 && !(type instanceof StructureDataType)) {
                                            storage = new VariableStorage(currentProgram, currentProgram.getRegister("EDX"));
                                        }
                                        else {
                                            if (type.isNotYetDefined()) {
                                                printfmt(
                                                    "Warning: function {0} has parameter {1} of an undefined " + 
                                                    "struct type - you will need to manually fix this later!",
                                                    fullName, param.getName()
                                                );
                                            }
                                            storage = new VariableStorage(currentProgram, stackOffset, type.getLength());
                                            stackOffset += reorderedParams.get(i).getLength();
                                        }
                                    }
                                    param.setDataType(type, storage, true, SourceType.ANALYSIS);
                                }
                            }
                            // Use dynamic storage for calling conventions Ghidra knows
                            else {
                                updateType = FunctionUpdateType.DYNAMIC_STORAGE_ALL_PARAMS;
                            }

                            if (didAddThis) {
                                importedAddCount += 1;
                                printfmt("Added {0}", fullName);
                            }
                            else if (didUpdateThis) {
                                importedUpdateCount += 1;
                                printfmt("Updated {0}", fullName);
                            }

                            // Apply new signature
                            data.updateFunction(
                                getCConvName(conv),
                                bromaRetType,
                                updateType,
                                true,
                                SourceType.ANALYSIS,
                                bromaParams.toArray(Variable[]::new)
                            );
                        }
                    );
                }
            );
        }

        printfmt("Added {0} functions & updated {1} functions from Broma", importedAddCount, importedUpdateCount);
    }

    void printfmt(String fmt, Object... args) {
        println(MessageFormat.format(fmt, args));
    }

    void matchAll(Pattern regex, String against, ThrowingConsumer<Matcher, Exception> forEach) throws Exception {
        var matcher = regex.matcher(against);
        while (matcher.find()) {
            forEach.accept(matcher);
        }
    }

    List<String> getPlatformOptions() {
        return List.of("Windows", "Mac");
    }

    String getPlatformLinkName(String platform) {
        switch (platform) {
            case "Windows": return "win";
            case "Mac": return "mac";
            default: throw new Error(
                "Invalid platform option - SyncBromaScript.getPlatformLinkName " + 
                "should be updated to match SyncBromaScript.getPlatformOptions"
            );
        }
    }

    Pattern getPlatformAddrPattern(String platform) {
        switch (platform) {
            case "Windows": return Regexes.GRAB_WIN_ADDRESS;
            case "Mac": return Regexes.GRAB_MAC_ADDRESS;
            default: throw new Error(
                "Invalid platform option - SyncBromaScript.getPlatformAddrPattern " + 
                "should be updated to match SyncBromaScript.getPlatformOptions"
            );
        }
    }

    CConv getCallingConvention(String platform, Boolean link, Matcher funMatcher) {
        if (!platform.equals("Windows")) {
            return null;
        }
        final var dispatch = funMatcher.group("dispatch");
        if (dispatch != null) {
            switch (dispatch) {
                case "virtual": case "callback": {
                    return CConv.THISCALL;
                }
                case "static": {
                    if (link) {
                        return CConv.CDECL;
                    }
                    return CConv.OPTCALL;
                }
            }
        }
        if (link) {
            return CConv.THISCALL;
        }
        return CConv.MEMBERCALL;
    }

    String getCConvName(CConv conv) {
        if (conv == null) {
            return null;
        }
        switch (conv) {
            case CDECL: return "__cdecl";
            case OPTCALL:
            case FASTCALL: return "__fastcall";
            case MEMBERCALL:
            case THISCALL: return "__thiscall";
        }
        return null;
    }

    Namespace parseNamespace(String string) throws Exception {
        Namespace ret = null;
        var iter = Arrays.asList(string.split("::")).listIterator();
        while (iter.hasNext()) {
            var ns = iter.next();
            var get = getNamespace(ret, ns);
            if (get == null) {
                if (iter.hasNext()) {
                    ret = currentProgram.getSymbolTable().createNameSpace(ret, ns, SourceType.ANALYSIS);
                }
                else {
                    ret = currentProgram.getSymbolTable().createClass(ret, ns, SourceType.ANALYSIS);
                }
            }
            ret = get;
        }
        return ret;
    }

    DataType parseType(String string) {
        final var manager = currentProgram.getDataTypeManager();
        var matcher = Regexes.GRAB_TYPE.matcher(string);
        if (!matcher.find()) {
            throw new Error(
                "Unable to match data type \"" + string + "\" with regex " + Regexes.GRAB_TYPE.pattern()
            );
        }

        // Get the name and category
        var nameIter = Arrays.asList(matcher.group("name").split("::")).listIterator();
        String name = null;
        DataTypePath typePath = null;
        CategoryPath category = new CategoryPath("/");
        while (nameIter.hasNext()) {
            var ns = nameIter.next();
            if (nameIter.hasNext()) {
                category = category.extend(ns);
                if (manager.getCategory(category) == null) {
                    manager.createCategory(category);
                }
            }
            else {
                // Add template parameters to the name
                var templates = matcher.group("template");
                if (templates != null) {
                    ns += templates;
                }
                name = ns;
                typePath = new DataTypePath(category, ns);
            }
        }
        if (name == null) {
            throw new Error("Data type doesn't have a name - this is an error in the SyncBromaScript GRAB_TYPE regex");
        }

        // Try to get this type
        var type = manager.getDataType(typePath);
        if (type == null) {
            // Try to guess the type; if the guess is wrong, the user can fix it manually
            // If the type is passed without pointer or reference, assume it's an enum
            if (matcher.group("ptr") == null && matcher.group("ref") == null) {
                type = manager.addDataType(
                    new EnumDataType(category, name, new IntegerDataType().getLength()),
                    DataTypeConflictHandler.DEFAULT_HANDLER
                );
                printfmt("Created new type {0}, assumed it's an enum", typePath);
            }
            // Otherwise it's probably a struct
            else {
                type = manager.addDataType(
                    new StructureDataType(category, name, 0),
                    DataTypeConflictHandler.DEFAULT_HANDLER
                );
                printfmt("Created new type {0}, assumed it's a struct", typePath);
            }
        }

        // Apply any modifiers

        if (matcher.group("lconst") != null || matcher.group("rconst") != null) {
            // Constants don't exist in Ghidra lol
        }
        // Make the type a pointer if it's a ptr or ref
        if (matcher.group("ptr") != null) {
            // Make sure to support multi-level pointers like int**
            for (var i = 0; i < matcher.group("ptr").length(); i++) {
                type = new PointerDataType(type);
            }
        }
        if (matcher.group("ref") != null) {
            for (var i = 0; i < matcher.group("ref").length(); i++) {
                type = new PointerDataType(type);
            }
        }

        return type;
    }

    <A, B> Boolean askBromaConflict(String in, String what, A broma, B ghidra) throws Exception {
        switch (askChoice(
            "Conflict between Broma and Ghidra",
            MessageFormat.format(
                "Conflict between {1}s in {0}:                   \n" + 
                "Broma: {2}                   \n" +
                "Ghidra: {3}                   \n" + 
                "Should Broma's {1} be used or keep Ghidra's {1}?",
                in, what, broma, ghidra
            ),
            List.of("Use Broma", "Keep Ghidra", "Cancel Script"),
            null
        )) {
            case "Use Broma": return true;
            case "Keep Ghidra": return false;
            case "Cancel Script": throw new Error("Script cancelled");
        }
        return true;
    }
}
