annotation class Ann(val t: String)

class Annotated @Ann("42") constructor<caret>()