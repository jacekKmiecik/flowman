package com.dimajix.flowman.sources.local.csv

import com.univocity.parsers.csv.CsvFormat
import com.univocity.parsers.csv.CsvWriterSettings


object CsvUtils {
    def createWriterSettings(options:CsvOptions) : CsvWriterSettings = {
        val format = new CsvFormat
        format.setLineSeparator(options.newline)
        format.setDelimiter(options.delimiter)
        format.setQuote(options.quote)
        format.setQuoteEscape(options.escape)
        val settings = new CsvWriterSettings
        settings.setFormat(format)
        settings.setQuoteAllFields(options.quoteAll)
        settings.setQuoteEscapingEnabled(options.escapeQuotes)
        settings.setSkipEmptyLines(true)
        settings.setNullValue(options.nullValue)
        settings.setEmptyValue(options.nullValue)
        settings.setIgnoreLeadingWhitespaces(options.ignoreLeadingWhiteSpaceFlag)
        settings.setIgnoreTrailingWhitespaces(options.ignoreTrailingWhiteSpaceFlag)
        settings
    }

    /**
      * Helper method that converts string representation of a character to actual character.
      * It handles some Java escaped strings and throws exception if given string is longer than one
      * character.
      */
    @throws[IllegalArgumentException]
    def toChar(str: String): Char = {
        if (str.charAt(0) == '\\') {
            str.charAt(1)
            match {
                case 't' => '\t'
                case 'r' => '\r'
                case 'b' => '\b'
                case 'f' => '\f'
                case '\"' => '\"' // In case user changes quote char and uses \" as delimiter in options
                case '\'' => '\''
                case '\\' => '\\'
                case 'u' if str == """\u0000""" => '\u0000'
                case _ =>
                    throw new IllegalArgumentException(s"Unsupported special character for delimiter: $str")
            }
        } else if (str.length == 1) {
            str.charAt(0)
        } else {
            throw new IllegalArgumentException(s"Delimiter cannot be more than one character: $str")
        }
    }
}
