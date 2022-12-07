package de.thm.mni.compilerbau.phases._04b_semant;

import de.thm.mni.compilerbau.CommandLineOptions;
import de.thm.mni.compilerbau.absyn.*;
import de.thm.mni.compilerbau.absyn.visitor.DoNothingVisitor;
import de.thm.mni.compilerbau.table.Entry;
import de.thm.mni.compilerbau.table.ProcedureEntry;
import de.thm.mni.compilerbau.table.SymbolTable;
import de.thm.mni.compilerbau.table.VariableEntry;
import de.thm.mni.compilerbau.types.ArrayType;
import de.thm.mni.compilerbau.types.PrimitiveType;
import de.thm.mni.compilerbau.types.Type;
import de.thm.mni.compilerbau.utils.SplError;

import java.util.Optional;

/**
 * This class is used to check if the currently compiled SPL program is semantically valid.
 * The body of each procedure has to be checked, consisting of {@link Statement}s, {@link Variable}s and {@link Expression}s.
 * Each node has to be checked for type issues or other semantic issues.
 * Calculated {@link Type}s can be stored in and read from the dataType field of the {@link Expression} and {@link Variable} classes.
 */
public class ProcedureBodyChecker extends DoNothingVisitor {

    private final CommandLineOptions options;
    SymbolTable table;

    public ProcedureBodyChecker(CommandLineOptions options) {
        this.options = options;
    }

    public void checkProcedures(Program program, SymbolTable globalTable) {
        this.table = globalTable;
        program.declarations.stream().filter(x -> x instanceof ProcedureDeclaration).forEach(x -> x.accept(this));
    }

    @Override
    public void visit(ProcedureDeclaration procedureDeclaration) {
        ProcedureEntry entry = (ProcedureEntry) table.lookup(procedureDeclaration.name);
        SymbolTable global = table;
        table = entry.localTable;
        procedureDeclaration.body.forEach(x -> x.accept(this));
        table = global;
    }

    @Override
    public void visit(WhileStatement whileStatement) {
        whileStatement.condition.accept(this);
        if(whileStatement.condition.dataType != PrimitiveType.boolType)
            throw SplError.WhileConditionMustBeBoolean(whileStatement.position, whileStatement.condition.dataType);
        whileStatement.body.accept(this);
    }

    @Override
    public void visit(IfStatement ifStatement) {
        ifStatement.condition.accept(this);
        if(ifStatement.condition.dataType != PrimitiveType.boolType)
            throw SplError.IfConditionMustBeBoolean(ifStatement.position, ifStatement.condition.dataType);
        ifStatement.thenPart.accept(this);
        ifStatement.elsePart.accept(this);
    }

    @Override
    public void visit(CallStatement callStatement) {
        Optional<Entry> result = table.find(callStatement.procedureName);
        if(result.isEmpty())
            throw SplError.UndefinedProcedure(callStatement.position, callStatement.procedureName);
        if(!(result.get() instanceof ProcedureEntry))
            throw SplError.CallOfNonProcedure(callStatement.position, callStatement.procedureName);
        ProcedureEntry entry = (ProcedureEntry) table.lookup(callStatement.procedureName);
        callStatement.arguments.forEach(x -> x.accept(this));

        if(callStatement.arguments.size() > entry.parameterTypes.size())
            throw SplError.TooManyArguments(callStatement.position, callStatement.procedureName);
        if(callStatement.arguments.size() < entry.parameterTypes.size())
            throw SplError.TooFewArguments(callStatement.position, callStatement.procedureName);

        for(int i = 0; i < callStatement.arguments.size(); i++) {
            Expression arg = callStatement.arguments.get(i);
            if(!arg.dataType.equals(entry.parameterTypes.get(i).type))
                throw SplError.ArgumentTypeMismatch(arg.position,
                        callStatement.procedureName,
                        i + 1,
                        entry.parameterTypes.get(i).type,
                        arg.dataType);
            if(entry.parameterTypes.get(i).isReference && !(arg instanceof VariableExpression))
                throw SplError.ArgumentMustBeAVariable(arg.position, callStatement.procedureName, i + 1);
        }
    }

    @Override
    public void visit(UnaryExpression unaryExpression) {
        unaryExpression.operand.accept(this);
        if(unaryExpression.operand.dataType == PrimitiveType.boolType)
            throw SplError.NoSuchOperator(unaryExpression.position, unaryExpression.operator, unaryExpression.dataType);
        unaryExpression.dataType = unaryExpression.operand.dataType;
    }

    @Override
    public void visit(BinaryExpression binaryExpression) {
        binaryExpression.leftOperand.accept(this);
        binaryExpression.rightOperand.accept(this);
        binaryExpression.dataType = binaryExpression.operator.isArithmetic() ? PrimitiveType.intType : PrimitiveType.boolType;
        if(!(binaryExpression.leftOperand.dataType.equals(binaryExpression.rightOperand.dataType))) {
            throw SplError.NoSuchOperator(binaryExpression.position,
                    binaryExpression.operator,
                    binaryExpression.leftOperand.dataType,
                    binaryExpression.rightOperand.dataType);
        }
    }

    @Override
    public void visit(CompoundStatement compoundStatement) {
        compoundStatement.statements.forEach(x -> x.accept(this));
    }

    @Override
    public void visit(IntLiteral intLiteral) {
        intLiteral.dataType = PrimitiveType.intType;
    }

    @Override
    public void visit(AssignStatement assignStatement) {
        assignStatement.target.accept(this);
        assignStatement.value.accept(this);

        if(assignStatement.target.dataType instanceof ArrayType)
            throw SplError.IllegalAssignmentToArray(assignStatement.position);

        if(!assignStatement.target.dataType.equals(assignStatement.value.dataType))
            throw SplError.IllegalAssignment(assignStatement.position,
                    assignStatement.target.dataType,
                    assignStatement.target.dataType);
    }

    @Override
    public void visit(VariableExpression variableExpression) {
        variableExpression.variable.accept(this);
        variableExpression.dataType = variableExpression.variable.dataType;
    }

    @Override
    public void visit(NamedVariable namedVariable) {
        Entry entry = table.lookup(namedVariable.name);
        if(!(entry instanceof VariableEntry))
            throw SplError.NotAVariable(namedVariable.position, namedVariable.name);
        namedVariable.dataType = ((VariableEntry) entry).type;
    }

    @Override
    public void visit(ArrayAccess arrayAccess) {
        arrayAccess.index.accept(this);
        if(!arrayAccess.index.dataType.equals(PrimitiveType.intType))
            throw SplError.IndexingWithNonInteger(arrayAccess.position);
        arrayAccess.array.accept(this);
        if(arrayAccess.array.dataType instanceof ArrayType)
            arrayAccess.dataType = ((ArrayType) arrayAccess.array.dataType).baseType;
        else
            throw SplError.IndexingNonArray(arrayAccess.position);
    }
}
