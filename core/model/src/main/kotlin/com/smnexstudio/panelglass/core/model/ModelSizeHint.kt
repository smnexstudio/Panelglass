package com.smnexstudio.panelglass.core.model

/**
 * What the size of a model says about it.
 *
 * A model of a billion parameters or less is usually too small to follow the translation format: it writes prose
 * instead of the JSON the contract asks for, and the reply has to be repaired or retried. The user is told, before
 * saving, when the model name gives that away — but never stopped, because some small models manage.
 */
object ModelSizeHint {
    /**
     * The size of the model in billions of parameters, when the name says it.
     *
     * The name is read for a number followed by `b`: `Qwen2.5-7B-Instruct` is 7, `gemma-3-270m` is not a size we
     * know, and `gpt-4o` says nothing at all. The **last** such number wins, because a name may carry more than one
     (`Llama-3.2-1B-Instruct-Q4` is the 1B one, not the 4).
     */
    fun parameterBillions(modelId: String): Double? {
        val matches = SIZE.findAll(modelId).toList()
        val last = matches.lastOrNull() ?: return null
        return last.groupValues[1].toDoubleOrNull()
    }

    /**
     * A number with a `b` after it, not part of a longer word.
     *
     * The look-behind keeps `x86b` from reading as 86, and the look-ahead keeps `b7` and `1bit` out.
     */
    private val SIZE = Regex("(?<![A-Za-z0-9.])(\\d+(?:\\.\\d+)?)[bB](?![A-Za-z0-9])")
}
