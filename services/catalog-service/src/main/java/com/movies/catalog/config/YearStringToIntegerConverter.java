package com.movies.catalog.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.ReadingConverter;

/**
 * Some sample_mflix documents store {@code year} as a mangled string (e.g. {@code "1986è"},
 * {@code "1994è1998"}) instead of a plain integer, likely from a bad en-dash-to-string
 * conversion upstream. Rather than 500ing on these rows, take the leading run of digits as
 * the year; if the string has no leading digits at all, map it to {@code null}.
 */
@ReadingConverter
public class YearStringToIntegerConverter implements Converter<String, Integer> {

    private static final Pattern LEADING_DIGITS = Pattern.compile("^\\s*(\\d+)");

    @Override
    public Integer convert(String source) {
        Matcher matcher = LEADING_DIGITS.matcher(source);
        return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
    }
}
