package app.tslink.engine;

// Callback into the tslink app process (see notify/EngineLink.kt).
oneway interface IEngineClient {
    // JSON message, e.g. {"t":"open","id":"n...","display":12}
    void toApp(String json);
}
