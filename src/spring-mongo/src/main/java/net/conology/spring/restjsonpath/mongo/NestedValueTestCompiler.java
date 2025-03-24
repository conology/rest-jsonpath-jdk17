package net.conology.spring.restjsonpath.mongo;


import net.conology.restjsonpath.PeekingIterator;
import net.conology.restjsonpath.ast.*;
import net.conology.spring.restjsonpath.mongo.ir.*;

import java.util.ArrayList;

public class NestedValueTestCompiler {

    private final RelativeQueryNode relativeQueryNode;
    private final MongoIrCompilerPass parent;
    private final MongoPropertyAssertion finalAssertion;

    public NestedValueTestCompiler(
        RelativeQueryNode relativeQueryNode,
        MongoIrCompilerPass parent,
        MongoPropertyAssertion finalAssertion
    ) {
        this.relativeQueryNode = relativeQueryNode;
        this.parent = parent;
        this.finalAssertion = finalAssertion;
    }

    public MongoPropertyCondition compile() {
        var nodes = PeekingIterator.of(relativeQueryNode.getSelectorNodes().iterator());

        MongoPropertyCondition head = null;
        MongoElementMatch tail = null;

        while (nodes.hasNext()) {
            var fieldSelector = compileSelector(nodes);
            var elementMatch = compileElementMatch(nodes);

            if (elementMatch == null && nodes.hasNext()) {
                // field selector should consume everything except for filters that result in elementMatch
                throw new AssertionError(
                    "illegal state. this indicates a mismatch between parser and compiler"
                );
            }

            var currentTest = createTest(fieldSelector, elementMatch);
            if (head == null) {
                head = currentTest;
            }
            if (tail != null) {
                tail.addTest(currentTest);
            }
            tail = elementMatch;
        }

        return head;
    }

    private MongoPropertyCondition createTest(MongoFieldSelector fieldSelector, MongoElementMatch elementMatch) {
        var assertion =
            elementMatch != null ? elementMatch
            : ( finalAssertion != null ? finalAssertion
                : parent.getExistenceAssertion()
            );
        return new MongoPropertyCondition(
            fieldSelector,
            assertion
        );
    }

    private MongoFieldSelector compileSelector(PeekingIterator<SelectorNode> nodes) {
        var path = new ArrayList<String>();

        while (nodes.hasNext()) {
            var next = nodes.peek();
            boolean handled;

            if (next == SelectorNode.Constant.WILDCARD) {
                handled = true;
            } else if (next instanceof IndexSelectorNode indexSelectorNode) {
                path.add(Integer.toString(indexSelectorNode.getIndex()));
                handled = true;
            } else if (next instanceof UnsafeFieldSelector it) {
                path.add(it.getFieldName());
                handled = true;
            } else if (next instanceof FieldSelectorNode fieldSelectorNode) {
                path.addAll(fieldSelectorNode.getPath());
                handled = true;
            } else if (next instanceof PropertyFilterNode) {
                handled = false;
            } else {
                throw new IllegalArgumentException("Unknown SelectorNode type: " + next);
            }

            if (handled) {
                nodes.next();
            } else {
                break;
            }
        }

        return new MongoFieldSelector(path);
    }

    private MongoElementMatch compileElementMatch(
        PeekingIterator<SelectorNode> nodes
    ) {
        var propertyTests = new ArrayList<MongoPropertyCondition>();
        while (nodes.hasNext()) {
            var next = nodes.peek();
            if (next instanceof PropertyFilterNode filterNode) {
                nodes.next();
                var testNode = parent.compileTestNode(filterNode);
                if (testNode instanceof MongoAllOfSelector allOf) {
                    propertyTests.addAll(allOf.getTests());
                }
                else if (testNode instanceof MongoPropertyCondition propertyTest) {
                    propertyTests.add(propertyTest);
                }
            } else {
                break;
            }
        }
        return propertyTests.isEmpty() ? null
            : new MongoElementMatch(new MongoAllOfSelector(propertyTests));
    }
}
