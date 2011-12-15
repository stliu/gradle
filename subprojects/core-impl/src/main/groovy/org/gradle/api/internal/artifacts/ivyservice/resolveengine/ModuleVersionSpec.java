/*
 * Copyright 2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.gradle.api.internal.artifacts.ivyservice.resolveengine;

import org.apache.ivy.core.module.descriptor.ExcludeRule;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.plugins.matcher.ExactPatternMatcher;
import org.apache.ivy.plugins.matcher.MatcherHelper;
import org.apache.ivy.plugins.matcher.PatternMatcher;
import org.gradle.api.specs.Spec;

import java.util.*;

/**
 * Manages sets of exclude rules, allowing union and intersection operations on the rules.
 */
public abstract class ModuleVersionSpec implements Spec<ModuleId> {
    private static final AcceptAllSpec ALL_SPEC = new AcceptAllSpec();

    public static ModuleVersionSpec forExcludes(ExcludeRule... excludeRules) {
        if (excludeRules.length == 0) {
            return ALL_SPEC;
        }
        return new ExcludeRuleBackedSpec(excludeRules);
    }

    public static ModuleVersionSpec forExcludes(Collection<ExcludeRule> excludeRules) {
        if (excludeRules.isEmpty()) {
            return ALL_SPEC;
        }
        return new ExcludeRuleBackedSpec(excludeRules.toArray(new ExcludeRule[excludeRules.size()]));
    }

    /**
     * Returns a spec which accepts the union of those module versions that are accepted by this spec, and a spec with the given exclude rules.
     *
     * @return The new spec. Returns this if the union == the set of module versions that are accepted by this spec.
     */
    public ModuleVersionSpec union(ModuleVersionSpec other) {
        if (other == this) {
            return this;
        }
        if (other == ALL_SPEC) {
            return other;
        }
        if (this == ALL_SPEC) {
            return this;
        }
        List<ModuleVersionSpec> specs = new ArrayList<ModuleVersionSpec>();
        unpackUnion(specs);
        other.unpackUnion(specs);
        for (int i = 0; i < specs.size(); ) {
            ModuleVersionSpec spec = specs.get(i);
            ModuleVersionSpec merged = null;
            for (int j = i + 1; j < specs.size(); j++) {
                merged = spec.doUnion(specs.get(j));
                if (merged != null) {
                    specs.remove(j);
                    break;
                }
            }
            if (merged != null) {
                specs.set(i, merged);
            } else {
                i++;
            }
        }
        if (specs.size() == 1) {
            return specs.get(0);
        }
        return new UnionSpec(specs);
    }

    protected void unpackUnion(Collection<ModuleVersionSpec> specs) {
        specs.add(this);
    }

    protected ModuleVersionSpec doUnion(ModuleVersionSpec other) {
        return null;
    }

    /**
     * Returns a spec which is accepts the intersection of those module versions that are accepted by this spec, and a spec with the given exclude rules.
     *
     * @return The new spec. Returns this if the intersection == the set of module versions that are accepted by this spec.
     */
    public ModuleVersionSpec intersect(ModuleVersionSpec other) {
        if (other == this) {
            return this;
        }
        if (other == ALL_SPEC) {
            return this;
        }
        if (this == ALL_SPEC) {
            return other;
        }
        return doIntersection(other);
    }

    protected ModuleVersionSpec doIntersection(ModuleVersionSpec other) {
        return new IntersectSpec(this, other);
    }

    private static class AcceptAllSpec extends ModuleVersionSpec {
        public boolean isSatisfiedBy(ModuleId element) {
            return true;
        }
    }

    static class ExcludeRuleBackedSpec extends ModuleVersionSpec {
        private final ExcludeRule[] excludeRules;

        private ExcludeRuleBackedSpec(ExcludeRule[] excludeRules) {
            this.excludeRules = excludeRules;
        }

        public boolean isSatisfiedBy(ModuleId element) {
            for (ExcludeRule excludeRule : excludeRules) {
                if (MatcherHelper.matches(excludeRule.getMatcher(), excludeRule.getId().getModuleId(), element)) {
                    return false;
                }
            }

            return true;
        }

        @Override
        protected ModuleVersionSpec doUnion(ModuleVersionSpec other) {
            if (!(other instanceof ExcludeRuleBackedSpec)) {
                return super.doUnion(other);
            }

            // Can't merge things other than exact patterns and can't merge wildcards, yet.
            Map<ModuleId, ExcludeRule> thisRules = new HashMap<ModuleId, ExcludeRule>();
            for (ExcludeRule rule : excludeRules) {
                ModuleId moduleId = rule.getId().getModuleId();
                if (rule.getMatcher() != ExactPatternMatcher.INSTANCE
                        || moduleId.getOrganisation().equals(PatternMatcher.ANY_EXPRESSION)
                        || moduleId.getName().equals(PatternMatcher.ANY_EXPRESSION)) {
                    return super.doUnion(other);
                }
                thisRules.put(moduleId, rule);
            }

            ExcludeRuleBackedSpec otherExcludeRuleSpec = (ExcludeRuleBackedSpec) other;
            Map<ModuleId, ExcludeRule> otherRules = new HashMap<ModuleId, ExcludeRule>();
            for (ExcludeRule rule : otherExcludeRuleSpec.excludeRules) {
                ModuleId moduleId = rule.getId().getModuleId();
                if (rule.getMatcher() != ExactPatternMatcher.INSTANCE
                        || moduleId.getOrganisation().equals(PatternMatcher.ANY_EXPRESSION)
                        || moduleId.getName().equals(PatternMatcher.ANY_EXPRESSION)) {
                    return super.doUnion(other);
                }
                otherRules.put(moduleId, rule);
            }

            thisRules.keySet().retainAll(otherRules.keySet());
            return forExcludes(thisRules.values());
        }

        @Override
        protected ModuleVersionSpec doIntersection(ModuleVersionSpec other) {
            if (!(other instanceof ExcludeRuleBackedSpec)) {
                return super.doIntersection(other);
            }
            
            ExcludeRuleBackedSpec otherExcludeRuleSpec = (ExcludeRuleBackedSpec) other;
            List<ExcludeRule> rules = new ArrayList<ExcludeRule>();
            rules.addAll(Arrays.asList(excludeRules));
            rules.addAll(Arrays.asList(otherExcludeRuleSpec.excludeRules));
            return forExcludes(rules);
        }
    }

    static class UnionSpec extends ModuleVersionSpec {
        private final List<ModuleVersionSpec> specs;

        public UnionSpec(List<ModuleVersionSpec> specs) {
            this.specs = specs;
        }

        @Override
        protected void unpackUnion(Collection<ModuleVersionSpec> specs) {
            specs.addAll(this.specs);
        }

        public boolean isSatisfiedBy(ModuleId element) {
            for (ModuleVersionSpec spec : specs) {
                if (spec.isSatisfiedBy(element)) {
                    return true;
                }
            }

            return false;
        }
    }
    
    private static class IntersectSpec extends ModuleVersionSpec {
        private final ModuleVersionSpec[] specs;

        private IntersectSpec(ModuleVersionSpec... specs) {
            this.specs = specs;
        }

        public boolean isSatisfiedBy(ModuleId element) {
            for (ModuleVersionSpec spec : specs) {
                if (!spec.isSatisfiedBy(element)) {
                    return false;
                }
            }
            return true;
        }
    }
}