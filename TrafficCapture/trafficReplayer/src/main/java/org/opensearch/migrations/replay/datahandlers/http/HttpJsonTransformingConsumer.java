package org.opensearch.migrations.replay.datahandlers.http;

import io.netty.buffer.ByteBuf;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpRequestDecoder;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.migrations.Utils;
import org.opensearch.migrations.coreutils.MetricsAttributeKey;
import org.opensearch.migrations.coreutils.MetricsEvent;
import org.opensearch.migrations.coreutils.MetricsLogger;
import org.opensearch.migrations.replay.tracing.IReplayContexts;
import org.opensearch.migrations.replay.datatypes.HttpRequestTransformationStatus;
import org.opensearch.migrations.replay.datatypes.TransformedOutputAndResult;
import org.opensearch.migrations.replay.datahandlers.IPacketFinalizingConsumer;
import org.opensearch.migrations.replay.util.DiagnosticTrackableCompletableFuture;
import org.opensearch.migrations.replay.util.StringTrackableCompletableFuture;
import org.opensearch.migrations.transform.IAuthTransformerFactory;
import org.opensearch.migrations.transform.IJsonTransformer;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * This class implements a packet consuming interface by using an EmbeddedChannel to write individual
 * packets through handlers that will parse the request's HTTP headers, determine what may need to
 * be done with the message, and continue to parse/handle/serialize the contents according to the
 * transformation and the headers present (such as for gzipped or chunked encodings).
 *
 * There will be a number of reasons that we need to reuse the source captured packets - both today
 * (for analysing and comparing) and in the future (for retrying transient network errors or
 * transformation errors).  With that in mind, the HttpJsonTransformer now keeps track of all of
 * the ByteBufs passed into it and can redrive them through the underlying network packet handler.
 * Cases where that would happen with this edit are where the payload wasn't being modified, nor
 * were the headers changed.  In those cases, the entire pipeline gets removed and the "baseline"
 * ones never get activated.  This class detects that the baseline (NettySendByteBufsToPacketHandlerHandler)
 * never processed data and will re-run the messages that it already had accumulated.
 *
 * A bigger question will be how to deal with network errors, where some packets have been sent to
 * the network.  If a partial response comes back, should that be reported to the user?  What if the
 * error was due to transformation, how would we be able to tell?
 */
@Slf4j
public class HttpJsonTransformingConsumer<R> implements IPacketFinalizingConsumer<TransformedOutputAndResult<R>> {
    public static final int HTTP_MESSAGE_NUM_SEGMENTS = 2;
    public static final int EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS = 4;
    private final RequestPipelineOrchestrator<R> pipelineOrchestrator;
    private final EmbeddedChannel channel;
    private static final MetricsLogger metricsLogger = new MetricsLogger("HttpJsonTransformingConsumer");
    private IReplayContexts.IRequestTransformationContext transformationContext;

    /**
     * Roughly try to keep track of how big each data chunk was that came into the transformer.  These values
     * are used to chop results up on the way back out.
     * Divide the chunk tracking into headers (index=0) and payload (index=1).
     */
    private final List<List<Integer>> chunkSizes;
    // This is here for recovery, in case anything goes wrong with a transformation & we want to
    // just dump it directly.  Notice that we're already storing all of the bytes until the response
    // comes back so that we can format the output.  These should be backed by the exact same
    // byte[] arrays, so the memory consumption should already be absorbed.
    private final List<ByteBuf> chunks;

    public HttpJsonTransformingConsumer(IJsonTransformer transformer,
                                        IAuthTransformerFactory authTransformerFactory,
                                        IPacketFinalizingConsumer<R> transformedPacketReceiver,
                                        IReplayContexts.IReplayerHttpTransactionContext httpTransactionContext) {
        transformationContext = httpTransactionContext.createTransformationContext();
        chunkSizes = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS);
        chunkSizes.add(new ArrayList<>(EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS));
        chunks = new ArrayList<>(HTTP_MESSAGE_NUM_SEGMENTS + EXPECTED_PACKET_COUNT_GUESS_FOR_HEADERS);
        channel = new EmbeddedChannel();
        pipelineOrchestrator = new RequestPipelineOrchestrator<>(chunkSizes, transformedPacketReceiver,
                authTransformerFactory, transformationContext);
        pipelineOrchestrator.addInitialHandlers(channel.pipeline(), transformer);
    }

    private NettySendByteBufsToPacketHandlerHandler<R> getOffloadingHandler() {
        return Optional.ofNullable(channel).map(c ->
                        (NettySendByteBufsToPacketHandlerHandler) c.pipeline()
                                .get(RequestPipelineOrchestrator.OFFLOADING_HANDLER_NAME))
                .orElse(null);
    }

    private HttpRequestDecoder getHttpRequestDecoderHandler() {
        return Optional.ofNullable(channel).map(c ->
                        (HttpRequestDecoder) c.pipeline()
                                .get(RequestPipelineOrchestrator.HTTP_REQUEST_DECODER_NAME))
                .orElse(null);
    }

    @Override
    public DiagnosticTrackableCompletableFuture<String, Void> consumeBytes(ByteBuf nextRequestPacket) {
        chunks.add(nextRequestPacket.duplicate().readerIndex(0).retain());
        chunkSizes.get(chunkSizes.size() - 1).add(nextRequestPacket.readableBytes());
        if (log.isTraceEnabled()) {
            byte[] copy = new byte[nextRequestPacket.readableBytes()];
            nextRequestPacket.duplicate().readBytes(copy);
            log.trace("HttpJsonTransformingConsumer[" + this + "]: writing into embedded channel: "
                    + new String(copy, StandardCharsets.UTF_8));
        }
        return StringTrackableCompletableFuture.completedFuture(null, ()->"initialValue")
                .map(cf->cf.thenAccept(x -> channel.writeInbound(nextRequestPacket)),
                        ()->"HttpJsonTransformingConsumer sending bytes to its EmbeddedChannel");
    }

    public DiagnosticTrackableCompletableFuture<String, TransformedOutputAndResult<R>> finalizeRequest() {
        var offloadingHandler = getOffloadingHandler();
        try {
            channel.checkException();
            if (getHttpRequestDecoderHandler() == null) { // LastHttpContent won't be sent
                channel.writeInbound(new EndOfInput());   // so send our own version of 'EOF'
            }
        } catch (Exception e) {
            this.transformationContext.addException(e);
            log.atLevel(e instanceof NettyJsonBodyAccumulateHandler.IncompleteJsonBodyException ?
                            Level.DEBUG : Level.WARN)
                    .setMessage("Caught IncompleteJsonBodyException when sending the end of content")
                    .setCause(e)
                    .log();
            return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver, e);
        } finally {
            var cf = channel.close();
            if (cf.cause() != null) {
                log.atInfo().setCause(cf.cause()).setMessage("Exception encountered during write").log();
            }
        }
        if (offloadingHandler == null) {
            // the NettyDecodedHttpRequestHandler gave up and didn't bother installing the baseline handlers -
            // redrive the chunks
            return redriveWithoutTransformation(pipelineOrchestrator.packetReceiver, null);
        }
        return offloadingHandler.getPacketReceiverCompletionFuture()
                .getDeferredFutureThroughHandle(
                        (v, t) -> {
                            if (t != null) {
                                transformationContext.onTransformFailure();
                                t = unwindPossibleCompletionException(t);
                                if (t instanceof NoContentException) {
                                    return redriveWithoutTransformation(offloadingHandler.packetReceiver, t);
                                } else {
                                    transformationContext.close();
                                    metricsLogger.atError(MetricsEvent.TRANSFORMING_REQUEST_FAILED, t)
                                            .setAttribute(MetricsAttributeKey.REQUEST_ID, transformationContext.toString())
                                            .setAttribute(MetricsAttributeKey.CONNECTION_ID, transformationContext.getLogicalEnclosingScope().getConnectionId())
                                            .setAttribute(MetricsAttributeKey.CHANNEL_ID, channel.id().asLongText()).emit();
                                    throw new CompletionException(t);
                                }
                            } else {
                                transformationContext.close();
                                transformationContext.onTransformSuccess();
                                metricsLogger.atSuccess(MetricsEvent.REQUEST_WAS_TRANSFORMED)
                                        .setAttribute(MetricsAttributeKey.REQUEST_ID, transformationContext)
                                        .setAttribute(MetricsAttributeKey.CONNECTION_ID, transformationContext.getLogicalEnclosingScope().getConnectionId())
                                        .setAttribute(MetricsAttributeKey.CHANNEL_ID, channel.id().asLongText()).emit();
                                return StringTrackableCompletableFuture.completedFuture(v, ()->"transformedHttpMessageValue");
                            }
                        }, ()->"HttpJsonTransformingConsumer.finalizeRequest() is waiting to handle");
    }

    private static Throwable unwindPossibleCompletionException(Throwable t) {
        while (t instanceof CompletionException) {
            t = t.getCause();
        }
        return t;
    }

    private DiagnosticTrackableCompletableFuture<String, TransformedOutputAndResult<R>>
    redriveWithoutTransformation(IPacketFinalizingConsumer<R> packetConsumer, Throwable reason) {
        DiagnosticTrackableCompletableFuture<String,Void> consumptionChainedFuture =
                chunks.stream().collect(
                        Utils.foldLeft(DiagnosticTrackableCompletableFuture.Factory.
                                        completedFuture(null, ()->"Initial value"),
                                (dcf, bb) -> dcf.thenCompose(v -> packetConsumer.consumeBytes(bb),
                                        ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation collect()")));
        DiagnosticTrackableCompletableFuture<String,R> finalizedFuture =
                consumptionChainedFuture.thenCompose(v -> packetConsumer.finalizeRequest(),
                        ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation.compose()");
        metricsLogger.atError(MetricsEvent.REQUEST_REDRIVEN_WITHOUT_TRANSFORMATION, reason)
                .setAttribute(MetricsAttributeKey.REQUEST_ID, transformationContext)
                .setAttribute(MetricsAttributeKey.CONNECTION_ID, transformationContext.getLogicalEnclosingScope().getConnectionId())
                .setAttribute(MetricsAttributeKey.CHANNEL_ID, channel.id().asLongText()).emit();
        return finalizedFuture.map(f->f.thenApply(r -> reason == null ?
                new TransformedOutputAndResult<>(r, HttpRequestTransformationStatus.SKIPPED, null) :
                new TransformedOutputAndResult<>(r, HttpRequestTransformationStatus.ERROR, reason)
        )
                        .whenComplete((v,t)->{
                            transformationContext.onTransformSkip();
                            transformationContext.close();
                        }),
                ()->"HttpJsonTransformingConsumer.redriveWithoutTransformation().map()");
    }
}