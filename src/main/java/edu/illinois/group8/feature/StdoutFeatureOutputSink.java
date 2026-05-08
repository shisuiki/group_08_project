package edu.illinois.group8.feature;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.illinois.group8.canonical.JsonCanonicalSerializer;

public class StdoutFeatureOutputSink implements FeatureOutputSink {
    private final ObjectMapper mapper = new JsonCanonicalSerializer().mapper();

    @Override
    public synchronized void write(FeatureOutput output) {
        try {
            System.out.println(mapper.writeValueAsString(output));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize feature output " + output.featureName(), e);
        }
    }
}
