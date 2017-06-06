package sangria.optics.agent

import org.parboiled2.Position
import sangria.ast
import sangria.ast.Argument
import sangria.ast.AstVisitor
import sangria.ast.BigDecimalValue
import sangria.ast.BigIntValue
import sangria.ast.BooleanValue
import sangria.ast.Document
import sangria.ast.EnumValue
import sangria.ast.Field
import sangria.ast.FloatValue
import sangria.ast.FragmentDefinition
import sangria.ast.FragmentSpread
import sangria.ast.InlineFragment
import sangria.ast.IntValue
import sangria.ast.ListValue
import sangria.ast.NullValue
import sangria.ast.ObjectValue
import sangria.ast.OperationDefinition
import sangria.ast.Selection
import sangria.ast.StringValue
import sangria.ast.VariableValue
import sangria.renderer.FixedQueryRenderer
import sangria.visitor.VisitorCommand

trait OpticsQueryNormalizer {
  def normalizeQuery(query: ast.Document): String
}

/**
  * Normalizes a GraphQL query according to the spec at
  * https://github.com/apollographql/optics-agent/blob/master/docs/signatures.md
  */
object OpticsQueryNormalizer {

  implicit val default = new OpticsQueryNormalizer {
    override def normalizeQuery(query: Document): String = {
      query.separateOperations.collect { case (Some(operationName), document) => operationName -> document }
        .headOption
        .map { case (operationName, document) =>
          val modifiedQuery =
            AstVisitor.visit(document, AstVisitor {
              case field: sangria.ast.Field =>
                val modifiedField = field.copy(
                  alias = None,
                  selections = sortSelections(field.selections))
                VisitorCommand.Transform(modifiedField)
              case fragmentDefinition: FragmentDefinition =>
                // TODO: see if there's a way to generalize all SelectionContainers here
                val modifiedFragmentDefinition = fragmentDefinition.copy(
                  selections = sortSelections(fragmentDefinition.selections))
                VisitorCommand.Transform(modifiedFragmentDefinition)
              case inlineFragment: InlineFragment =>
                val modifiedInlineFragment = inlineFragment.copy(
                  selections = sortSelections(inlineFragment.selections))
                VisitorCommand.Transform(modifiedInlineFragment)
              case operationDefinition: OperationDefinition =>
                val modifiedOperationDefinition = operationDefinition.copy(
                  selections = sortSelections(operationDefinition.selections))
                VisitorCommand.Transform(modifiedOperationDefinition)
              case argument: Argument =>
                val modifiedValue = argument.value match {

                  // Remove non-boolean primitives, lists, and objects
                  case intValue: IntValue => intValue.copy(value = 0)
                  case bigIntValue: BigIntValue => bigIntValue.copy(value = 0)
                  case floatValue: FloatValue => floatValue.copy(value = 0)
                  case bigDecimalValue: BigDecimalValue => bigDecimalValue.copy(value = 0)
                  case stringValue: StringValue => stringValue.copy(value = "")
                  case listValue: ListValue => listValue.copy(values = Vector.empty)
                  case objectValue: ObjectValue => objectValue.copy(fields = Vector.empty)

                  // Preserve booleans, variables, and enums
                  case booleanValue: BooleanValue => booleanValue
                  case variableValue: VariableValue => variableValue
                  case enumValue: EnumValue => enumValue

                  // Nothing to do for null values
                  case nullValue: NullValue => nullValue

                }
                VisitorCommand.Transform(argument.copy(value = modifiedValue))
            })
          val rendererConfig = FixedQueryRenderer.Compact.copy(
            definitionSeparator = "",
            mandatoryLineBreak = " ")
          
          FixedQueryRenderer.render(modifiedQuery, rendererConfig)
        }
        .getOrElse("")
    }

  }


  implicit val positionOrdering: Ordering[Position] = {
    Ordering.by(_.index)
  }

  def sortSelections(selections: Vector[Selection]): Vector[Selection] = {
    val fields = selections
      .collect { case field: Field => field }
      .sortBy(field => (field.name, field.position))
    val fragmentSpreads = selections
      .collect { case fragmentSpread: FragmentSpread => fragmentSpread }
      .sortBy(fragmentSpread => (fragmentSpread.name, fragmentSpread.position))
    val inlineFragments = selections
      .collect { case inlineFragment: InlineFragment => inlineFragment }
      .sortBy(_.position)

    fields ++ fragmentSpreads ++ inlineFragments

  }
}
