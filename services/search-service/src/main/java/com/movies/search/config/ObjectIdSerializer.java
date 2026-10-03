package com.movies.search.config;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import java.io.IOException;
import org.bson.types.ObjectId;

/**
 * Serializes MongoDB's ObjectId as its hex string, not Jackson's base64 default.
 */
public class ObjectIdSerializer extends StdSerializer<ObjectId> {

    public ObjectIdSerializer() {
        super(ObjectId.class);
    }

    @Override
    public void serialize(ObjectId value, JsonGenerator gen, SerializerProvider provider)
            throws IOException {
        gen.writeString(value.toHexString());
    }
}
