package graphql.nadel

import graphql.ErrorType
import graphql.ExecutionResult
import graphql.GraphQLError
import graphql.execution.instrumentation.InstrumentationContext
import graphql.execution.instrumentation.InstrumentationState
import graphql.language.Document
import graphql.nadel.instrumentation.NadelInstrumentation
import graphql.nadel.instrumentation.parameters.NadelInstrumentationCreateStateParameters
import graphql.nadel.instrumentation.parameters.NadelInstrumentationExecuteOperationParameters
import graphql.nadel.instrumentation.parameters.NadelInstrumentationQueryExecutionParameters
import graphql.nadel.instrumentation.parameters.NadelNadelInstrumentationQueryValidationParameters
import graphql.nadel.testutils.TestUtil
import graphql.validation.ValidationError
import spock.lang.Specification

import static graphql.execution.instrumentation.SimpleInstrumentationContext.noOp
import static graphql.language.AstPrinter.printAstCompact
import static graphql.nadel.Nadel.newNadel
import static graphql.nadel.NadelExecutionInput.newNadelExecutionInput
import static java.util.concurrent.CompletableFuture.completedFuture

class NadelE2ETest extends Specification {

    def simpleNDSL = """
         service MyService {
            type Query{
                hello: World  
            } 
            type World {
                id: ID
                name: String
            }
            type Mutation{
                hello: String  
            } 
         }
        """

    def simpleUnderlyingSchema = TestUtil.schema("""
            type Query{
                hello: World  
            } 
            type World {
                id: ID
                name: String
            }
            type Mutation{
                hello: String  
            } 
        """)

    def delegatedExecution = Mock(ServiceExecution)
    def serviceFactory = TestUtil.serviceFactory(delegatedExecution, simpleUnderlyingSchema)

    def "query to one service with execution input passed down"() {

        given:
        def query = """
        query OpName { hello {name} hello {id} }
        """

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceDataFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables(["var1": "val1"])
                .context("contextObj")
                .operationName("OpName")
                .build()
        def data = [hello: [id: "3", name: "earth"]]

        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "query OpName {hello {name} hello {id}}"
            assert params.context == "contextObj"
            assert params.operationDefinition.name == "OpName"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.get().data == data
    }

    class TestState implements InstrumentationState {
    }

    def "instrumentation is called"() {

        given:
        def query = """
        query OpName { hello {name} hello {id} }
        """

        def variables = ["var1": "val1"]

        def instrumentationCalled = false
        def instrumentationParseCalled = false
        def instrumentationValidateCalled = false
        def instrumentationExecuteCalled = false
        NadelInstrumentation instrumentation = new NadelInstrumentation() {
            @Override
            InstrumentationState createState(NadelInstrumentationCreateStateParameters parameters) {
                return new TestState()
            }

            @Override
            InstrumentationContext<ExecutionResult> beginQueryExecution(NadelInstrumentationQueryExecutionParameters parameters) {
                instrumentationCalled = true
                parameters.instrumentationState instanceof TestState
                parameters.query == query
                parameters.variables == variables
                parameters.operation == "OpName"
                noOp()
            }

            @Override
            InstrumentationContext<Document> beginParse(NadelInstrumentationQueryExecutionParameters parameters) {
                instrumentationParseCalled = true
                noOp()
            }

            @Override
            InstrumentationContext<List<ValidationError>> beginValidation(NadelNadelInstrumentationQueryValidationParameters parameters) {
                instrumentationValidateCalled = true
                noOp()
            }

            @Override
            InstrumentationContext<ExecutionResult> beginExecute(NadelInstrumentationExecuteOperationParameters parameters) {
                instrumentationExecuteCalled = true
                noOp()
            }
        }

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceDataFactory(serviceFactory)
                .instrumentation(instrumentation)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .variables(variables)
                .context("contextObj")
                .operationName("OpName")
                .build()
        when:
        nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            def data = [hello: [id: "3", name: "earth"]]
            completedFuture(new ServiceExecutionResult(data))
        }
        instrumentationCalled
        instrumentationParseCalled
        instrumentationValidateCalled
        instrumentationExecuteCalled
    }

    def "graphql-java validation is invoked"() {
        given:
        def query = '''
        query OpName($unusedVariable : String) { hello {name} hello {id} }
        '''

        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceDataFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        when:
        def result = nadel.execute(nadelExecutionInput).join()

        then:
        !result.errors.isEmpty()
        def error = result.errors[0] as GraphQLError
        error.errorType == ErrorType.ValidationError

    }

    def "query to two services with field rename"() {

        def nsdl = '''
         service Foo {
            type Query{
                foo: Foo  <= \$source.fooOriginal 
            } 
            type Foo {
                name: String
            }
         }
         service Bar {
            type Query{
                bar: Bar 
            } 
            type Bar {
                name: String <= \$source.title
            }
         }
        '''
        def query = '''
        { otherFoo: foo {name} bar{name}}
        '''
        def underlyingSchema1 = TestUtil.schema('''
            type Query{
                fooOriginal: Foo  
                
            } 
            type Foo {
                name: String
            }
        ''')
        def underlyingSchema2 = TestUtil.schema('''
            type Query{
                bar: Bar 
            } 
            type Bar {
                title: String
            }
        ''')
        ServiceExecution delegatedExecution1 = Mock(ServiceExecution)
        ServiceExecution delegatedExecution2 = Mock(ServiceExecution)

        ServiceDataFactory serviceFactory = TestUtil.serviceFactory([
                Foo: new Tuple2(delegatedExecution1, underlyingSchema1),
                Bar: new Tuple2(delegatedExecution2, underlyingSchema2)]
        )

        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceDataFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data1 = [otherFoo: [name: "Foo"]]
        def data2 = [bar: [title: "Bar"]]
        ServiceExecutionResult delegatedExecutionResult1 = new ServiceExecutionResult(data1)
        ServiceExecutionResult delegatedExecutionResult2 = new ServiceExecutionResult(data2)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution1.execute(_) >> completedFuture(delegatedExecutionResult1)
        1 * delegatedExecution2.execute(_) >> completedFuture(delegatedExecutionResult2)
        result.get().data == [otherFoo: [name: "Foo"], bar: [name: "Bar"]]
    }

    def "query with three nested hydrations"() {

        def nsdl = '''
         service Foo {
            type Query{
                foo: Foo  
            } 
            type Foo {
                name: String
                bar: Bar <= \$innerQueries.Bar.barById(id: \$source.barId)
            }
         }
         service Bar {
            type Query{
                bar: Bar 
            } 
            type Bar {
                name: String 
                nestedBar: Bar <= \$innerQueries.Bar.barById(id: \$source.nestedBarId)
            }
         }
        '''
        def underlyingSchema1 = TestUtil.schema('''
            type Query{
                foo: Foo  
            } 
            type Foo {
                name: String
                barId: ID
            }
        ''')
        def underlyingSchema2 = TestUtil.schema('''
            type Query{
                bar: Bar 
                barById(id: ID): Bar
            } 
            type Bar {
                id: ID
                name: String
                nestedBarId: ID
            }
        ''')

        def query = '''
            { foo { bar { name nestedBar {name nestedBar { name } } } } }
        '''
        ServiceExecution serviceExecution1 = Mock(ServiceExecution)
        ServiceExecution serviceExecution2 = Mock(ServiceExecution)

        ServiceDataFactory serviceFactory = TestUtil.serviceFactory([
                Foo: new Tuple2(serviceExecution1, underlyingSchema1),
                Bar: new Tuple2(serviceExecution2, underlyingSchema2)]
        )
        given:
        Nadel nadel = newNadel()
                .dsl(nsdl)
                .serviceDataFactory(serviceFactory)
                .build()

        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()

        def topLevelData = [foo: [barId: "barId123"]]
        def hydrationData1 = [barById: [name: "BarName", nestedBarId: "nestedBarId123"]]
        def hydrationData2 = [barById: [name: "NestedBarName1", nestedBarId: "nestedBarId456"]]
        def hydrationData3 = [barById: [name: "NestedBarName2"]]
        ServiceExecutionResult topLevelResult = new ServiceExecutionResult(topLevelData)
        ServiceExecutionResult hydrationResult1 = new ServiceExecutionResult(hydrationData1)
        ServiceExecutionResult hydrationResult2 = new ServiceExecutionResult(hydrationData2)
        ServiceExecutionResult hydrationResult3 = new ServiceExecutionResult(hydrationData3)
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * serviceExecution1.execute(_) >>

                completedFuture(topLevelResult)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult1)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult2)

        1 * serviceExecution2.execute(_) >>

                completedFuture(hydrationResult3)

        result.get().data == [foo: [bar: [name: "BarName", nestedBar: [name: "NestedBarName1", nestedBar: [name
                                                                                                           : "NestedBarName2"]]]]]
    }

    def 'mutation can be executed'() {

        def query = '''
        mutation M{ hello }
        '''

        given:
        Nadel nadel = newNadel()
                .dsl(simpleNDSL)
                .serviceDataFactory(serviceFactory)
                .build()
        NadelExecutionInput nadelExecutionInput = newNadelExecutionInput()
                .query(query)
                .build()
        def data = [hello: "world"]
        when:
        def result = nadel.execute(nadelExecutionInput)

        then:
        1 * delegatedExecution.execute(_) >> { args ->
            ServiceExecutionParameters params = args[0]
            assert printAstCompact(params.query) == "mutation M {hello}"
            completedFuture(new ServiceExecutionResult(data))
        }
        result.get().data == data
    }

}