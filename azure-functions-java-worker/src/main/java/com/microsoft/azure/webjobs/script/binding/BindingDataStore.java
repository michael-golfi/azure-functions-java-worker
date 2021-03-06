package com.microsoft.azure.webjobs.script.binding;

import java.lang.reflect.*;
import java.util.*;
import java.util.function.*;

import com.microsoft.azure.webjobs.script.*;
import com.microsoft.azure.webjobs.script.binding.BindingData.*;
import com.microsoft.azure.webjobs.script.binding.BindingDefinition.*;
import com.microsoft.azure.webjobs.script.broker.CoreTypeResolver;
import com.microsoft.azure.webjobs.script.rpc.messages.*;

import javax.rmi.CORBA.Util;

import static com.microsoft.azure.webjobs.script.binding.BindingData.MatchingLevel.*;
import static com.microsoft.azure.webjobs.script.binding.BindingDefinition.BindingType.*;

/**
 * A warehouse storing all binding related information including actual binding value as well as binding declaration info.
 * This warehouse also provides methods to validate/look-up/convert against Java types.
 * Thread-safety: Single thread.
 */
public final class BindingDataStore {
    public BindingDataStore() {
        this.sources = new ArrayList<>();
        this.targets = new HashMap<>();
    }

    ///////////////////////// region Input Binding Data

    public void addParameterSources(List<ParameterBinding> parameters) {
        assert parameters != null;
        for (ParameterBinding parameter : parameters) {
            this.sources.add(rpcSourceFromParameter(parameter));
        }
    }

    public void addTriggerMetadataSource(Map<String, TypedData> metadata) {
        this.sources.add(new RpcTriggerMetadataDataSource(metadata));
    }

    public void addExecutionContextSource(String invocationId) {
        this.sources.add(new ExecutionContextDataSource(invocationId));
    }

    public Optional<BindingData> getDataByName(String name, Type target) {
        return this.getDataByLevels((s, l) -> s.computeByName(l, name, target), BINDING_NAME, METADATA_NAME);
    }

    public Optional<BindingData> getDataByType(Type target) {
        return this.getDataByLevels((s, l) -> s.computeByType(l, target), TYPE_ASSIGNMENT, TYPE_STRICT_CONVERSION, TYPE_RELAXED_CONVERSION);
    }

    private Optional<BindingData> getDataByLevels(BiFunction<DataSource<?>, MatchingLevel, Optional<BindingData>> worker, MatchingLevel... levels) {
        for (MatchingLevel level : levels) {
            List<BindingData> data = Utility.take(this.sources, 2, src -> worker.apply(src, level));
            if (data.size() > 0) { return Optional.ofNullable(data.size() == 1 ? data.get(0) : null); }
        }
        return Optional.empty();
    }

    static DataSource<?> rpcSourceFromTypedData(String name, TypedData data) {
        switch (data.getDataCase()) {
            case INT:    return new RpcIntegerDataSource(name, data.getInt());
            case DOUBLE: return new RpcRealNumberDataSource(name, data.getDouble());
            case STRING: return new RpcStringDataSource(name, data.getString());
            case BYTES:  return new RpcByteArrayDataSource(name, data.getBytes());
            case JSON:   return new RpcJsonDataSource(name, data.getJson());
            case HTTP:   return new RpcHttpRequestDataSource(name, data.getHttp());
            case DATA_NOT_SET: return new RpcEmptyDataSource(name);
            default:     throw new UnsupportedOperationException("Input data type \"" + data.getDataCase() + "\" is not supported");
        }
    }

    private static DataSource<?> rpcSourceFromParameter(ParameterBinding parameter) {
        return rpcSourceFromTypedData(parameter.getName(), parameter.getData());
    }

    ///////////////////////// endregion Input Binding Data

    ///////////////////////// region Output Binding Data

    public List<ParameterBinding> getOutputParameterBindings(boolean excludeReturn) {
        List<ParameterBinding> bindings = new ArrayList<>();
        for (Map.Entry<String, DataTarget> entry : this.targets.entrySet()) {
            if (!excludeReturn || !entry.getKey().equals(RETURN_NAME)) {
                entry.getValue().computeFromValue().ifPresent(data ->
                    bindings.add(ParameterBinding.newBuilder().setName(entry.getKey()).setData(data).build())
                );
            }
        }
        return bindings;
    }

    public Optional<TypedData> getDataTargetTypedValue(String name) {
        DataTarget output = this.targets.get(name);
        if (output == null) { return Optional.empty(); }
        return output.computeFromValue();
    }

    public Optional<BindingData> getOrAddDataTarget(String name, Type target) {
        DataTarget output = null;
        if (this.isDataTargetValid(name, target)) {
            output = this.targets.get(name);
            if (output == null && this.isDefinitionOutput(name)) {
                this.targets.put(name, output = rpcDataTargetFromType(target));
            }
        }
        return Optional.ofNullable(output).map(out -> new BindingData(out, BINDING_NAME));
    }

    public void setDataTargetValue(String name, Object value) {
        Optional.ofNullable(this.targets.get(name)).ifPresent(out -> out.setValue(value));
    }

    private boolean isDataTargetValid(String name, Type target) {
        if (!name.equals(RETURN_NAME)) {
            if (!CoreTypeResolver.isValidOutputType(target)) { return false; }
            target = CoreTypeResolver.getOutputParameterArgument(target);
        }
        if (CoreTypeResolver.isHttpResponse(target) && !this.isDefinitionBindingType(name, HTTP)) {
            return false;
        }
        return true;
    }

    private static DataTarget rpcDataTargetFromType(Type target) {
        if (CoreTypeResolver.isHttpResponse(target)) {
            return new RpcHttpDataTarget();
        }
        return new RpcUnspecifiedDataTarget();
    }

    ///////////////////////// endregion Output Binding Data

    ///////////////////////// region Binding Definitions

    public void setBindingDefinitions(Map<String, BindingDefinition> definitions) {
        this.definitions = definitions;
    }

    private boolean isDefinitionBindingType(String name, BindingType type) {
        return this.getDefinition(name).map(def -> def.getBindingType() == type).orElse(false);
    }

    private boolean isDefinitionOutput(String name) {
        return this.getDefinition(name).map(BindingDefinition::isOutput).orElse(false);
    }

    Optional<BindingDefinition> getTheOnlyDefinitionOfType(BindingType type) {
        List<BindingDefinition> matched = Utility.take(this.definitions.values(), 2, def ->
                Optional.ofNullable(def.getBindingType() == type ? def : null));
        return Optional.ofNullable(matched.size() == 1 ? matched.get(0) : null);
    }

    private Optional<BindingDefinition> getDefinition(String name) {
        return Optional.ofNullable(this.definitions.get(name));
    }

    ///////////////////////// endregion Binding Definitions

    private final List<DataSource<?>> sources;
    private final Map<String, DataTarget> targets;
    private Map<String, BindingDefinition> definitions;
    public static final String RETURN_NAME = "$return";
}
