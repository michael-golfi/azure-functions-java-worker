package com.microsoft.azure.webjobs.script.broker;

import java.lang.invoke.*;
import java.lang.reflect.*;
import java.util.*;

import com.microsoft.azure.serverless.functions.*;
import com.microsoft.azure.serverless.functions.annotation.*;
import com.microsoft.azure.webjobs.script.*;
import com.microsoft.azure.webjobs.script.binding.*;

/**
 * Resolve a Java method overload using reflection.
 * Thread-Safety: Multiple thread.
 */
class OverloadResolver {
    OverloadResolver() {
        this.candidates = new ArrayList<>();
    }

    synchronized void addCandidate(Method method) {
        this.candidates.add(new MethodBindInfo(method));
    }

    synchronized Optional<JavaMethodInvokeInfo> resolve(BindingDataStore dataStore) {
        return Utility.singleMax(Utility.mapOptional(this.candidates, m -> this.resolve(m, dataStore)),
            Comparator.<InvokeInfoBuilder>comparingInt(info -> info.matchingLevelCount[BindingData.MatchingLevel.BINDING_NAME.getIndex()])
                    .thenComparingInt(info -> info.matchingLevelCount[BindingData.MatchingLevel.METADATA_NAME.getIndex()])
                    .thenComparingInt(info -> info.matchingLevelCount[BindingData.MatchingLevel.TYPE_ASSIGNMENT.getIndex()])
                    .thenComparingInt(info -> info.matchingLevelCount[BindingData.MatchingLevel.TYPE_STRICT_CONVERSION.getIndex()])
                    .thenComparingInt(info -> info.matchingLevelCount[BindingData.MatchingLevel.TYPE_RELAXED_CONVERSION.getIndex()])
        ).map(InvokeInfoBuilder::build);
    }

    private Optional<InvokeInfoBuilder> resolve(MethodBindInfo method, BindingDataStore dataStore) {
        try {
            final InvokeInfoBuilder invokeInfo = new InvokeInfoBuilder(method);
            Utility.forEach(method.params, param -> {
                Optional<BindingData> argument;
                if (OutputBinding.class.isAssignableFrom(param.type)) {
                    argument = dataStore.getOrAddDataTarget(param.name, param.type);
                } else if (param.name != null && !param.name.isEmpty()) {
                    argument = dataStore.getDataByName(param.name, param.type);
                } else {
                    argument = dataStore.getDataByType(param.type);
                }
                BindingData actualArg = argument.orElseThrow(WrongMethodTypeException::new);
                invokeInfo.matchingLevelCount[actualArg.getLevel().getIndex()]++;
                invokeInfo.appendArgument(actualArg.getValue());
            });
            if (!method.entry.getReturnType().equals(void.class) && !method.entry.getReturnType().equals(Void.class)) {
                dataStore.getOrAddDataTarget(BindingDataStore.RETURN_NAME, method.entry.getReturnType());
            }
            return Optional.of(invokeInfo);
        } catch (Exception ex) {
            return Optional.empty();
        }
    }

    private final class InvokeInfoBuilder extends JavaMethodInvokeInfo.Builder {
        InvokeInfoBuilder(MethodBindInfo method) { super.setMethod(method.entry); }
        private final int[] matchingLevelCount = new int[BindingData.MatchingLevel.count()];
    }

    private final class MethodBindInfo {
        MethodBindInfo(Method m) {
            this.entry = m;
            this.params = Utility.map(this.entry.getParameters(), ParamBindInfo.class, ParamBindInfo::new);
        }
        private final Method entry;
        private final ParamBindInfo[] params;
    }

    private final class ParamBindInfo {
        ParamBindInfo(Parameter param) {
            this.name = CoreTypeResolver.getBindingName(param);
            this.type = param.getType();
        }
        private final String name;
        private final Class<?> type;
    }

    private final List<MethodBindInfo> candidates;
}
