package graphql.nadel.dsl;

import graphql.language.AbstractNode;
import graphql.language.Comment;
import graphql.language.IgnoredChars;
import graphql.language.Node;
import graphql.language.NodeChildrenContainer;
import graphql.language.NodeVisitor;
import graphql.language.SourceLocation;
import graphql.util.TraversalControl;
import graphql.util.TraverserContext;

import java.util.ArrayList;
import java.util.List;

public class FieldTransformation extends AbstractNode<FieldTransformation> {

    private final FieldMappingDefinition fieldMappingDefinition;
    private final UnderlyingServiceHydration underlyingServiceHydration;

    public FieldTransformation(FieldMappingDefinition fieldMappingDefinition, SourceLocation sourceLocation, List<Comment> comments) {
        super(sourceLocation, comments, IgnoredChars.EMPTY);
        this.fieldMappingDefinition = fieldMappingDefinition;
        this.underlyingServiceHydration = null;
    }

    public FieldTransformation(UnderlyingServiceHydration underlyingServiceHydration, SourceLocation sourceLocation, List<Comment> comments) {
        super(sourceLocation, comments, IgnoredChars.EMPTY);
        this.fieldMappingDefinition = null;
        this.underlyingServiceHydration = underlyingServiceHydration;
    }

    public FieldMappingDefinition getFieldMappingDefinition() {
        return fieldMappingDefinition;
    }

    public UnderlyingServiceHydration getUnderlyingServiceHydration() {
        return underlyingServiceHydration;
    }

    @Override
    public List<Node> getChildren() {
        return new ArrayList<>();
    }

    @Override
    public NodeChildrenContainer getNamedChildren() {
        return null;
    }

    @Override
    public FieldTransformation withNewChildren(NodeChildrenContainer newChildren) {
        return null;
    }

    @Override
    public boolean isEqualTo(Node node) {
        return false;
    }

    @Override
    public FieldTransformation deepCopy() {
        return null;
    }

    @Override
    public TraversalControl accept(TraverserContext<Node> context, NodeVisitor visitor) {
        return null;
    }
}
