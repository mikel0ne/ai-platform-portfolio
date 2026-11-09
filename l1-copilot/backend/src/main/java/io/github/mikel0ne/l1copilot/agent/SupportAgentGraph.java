package io.github.mikel0ne.l1copilot.agent;

import io.github.mikel0ne.l1copilot.agent.node.CancelCheckNode;
import io.github.mikel0ne.l1copilot.agent.node.CancelExecNode;
import io.github.mikel0ne.l1copilot.agent.node.ChattyNode;
import io.github.mikel0ne.l1copilot.agent.node.ClarifyNode;
import io.github.mikel0ne.l1copilot.agent.node.FallbackNode;
import io.github.mikel0ne.l1copilot.agent.node.FaqNode;
import io.github.mikel0ne.l1copilot.agent.node.ResolveOrderNode;
import io.github.mikel0ne.l1copilot.agent.node.RespondNode;
import io.github.mikel0ne.l1copilot.agent.node.ReturnCheckNode;
import io.github.mikel0ne.l1copilot.agent.node.ReturnExecNode;
import io.github.mikel0ne.l1copilot.agent.node.RouterNode;
import io.github.mikel0ne.l1copilot.agent.node.SupportNode;
import io.github.mikel0ne.l1copilot.agent.node.TicketsNode;
import io.github.mikel0ne.l1copilot.agent.node.TrackingNode;
import io.github.mikel0ne.l1copilot.agent.node.VerifyNode;
import lombok.RequiredArgsConstructor;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

@Component
@RequiredArgsConstructor
public class SupportAgentGraph {

    private final RouterNode routerNode;
    private final VerifyNode verifyNode;
    private final ClarifyNode clarifyNode;
    private final ResolveOrderNode resolveOrderNode;
    private final TrackingNode trackingNode;
    private final CancelCheckNode cancelCheckNode;
    private final CancelExecNode cancelExecNode;
    private final ReturnCheckNode returnCheckNode;
    private final ReturnExecNode returnExecNode;
    private final SupportNode supportNode;
    private final TicketsNode ticketsNode;
    private final FaqNode faqNode;
    private final ChattyNode chattyNode;
    private final FallbackNode fallbackNode;
    private final RespondNode respondNode;

    public StateGraph<SupportState> build() throws GraphStateException {
        return new StateGraph<>(SupportState.SCHEMA, SupportState::new)
                .addNode("router", node_async(routerNode))
                .addNode("verify", node_async(verifyNode))
                .addNode("clarify", node_async(clarifyNode))
                .addNode("resolve_order", node_async(resolveOrderNode))
                .addNode("tracking", node_async(trackingNode))
                .addNode("cancel_check", node_async(cancelCheckNode))
                .addNode("cancel_exec", node_async(cancelExecNode))
                .addNode("return_check", node_async(returnCheckNode))
                .addNode("return_exec", node_async(returnExecNode))
                .addNode("support", node_async(supportNode))
                .addNode("tickets", node_async(ticketsNode))
                .addNode("faq", node_async(faqNode))
                .addNode("chatty", node_async(chattyNode))
                .addNode("fallback", node_async(fallbackNode))
                .addNode("respond", node_async(respondNode))

                .addEdge(START, "router")

                .addConditionalEdges("router",
                        edge_async(SupportRouting::fromRouter),
                        Map.of(
                                SupportRouting.TO_VERIFY, "verify",
                                SupportRouting.TO_RESOLVE_ORDER, "resolve_order",
                                SupportRouting.TO_TRACKING, "tracking",
                                SupportRouting.TO_CANCEL_CHECK, "cancel_check",
                                SupportRouting.TO_RETURN_CHECK, "return_check",
                                SupportRouting.TO_SUPPORT, "support",
                                SupportRouting.TO_TICKETS, "tickets",
                                SupportRouting.TO_FAQ, "faq",
                                SupportRouting.TO_CHATTY, "chatty",
                                SupportRouting.TO_FALLBACK, "fallback"))

                .addConditionalEdges("verify",
                        edge_async(SupportRouting::fromVerify),
                        Map.of(
                                SupportRouting.TO_TRACKING, "tracking",
                                SupportRouting.TO_CANCEL_CHECK, "cancel_check",
                                SupportRouting.TO_RETURN_CHECK, "return_check",
                                SupportRouting.TO_RESOLVE_ORDER, "resolve_order",
                                SupportRouting.TO_SUPPORT, "support",
                                SupportRouting.TO_CLARIFY, "clarify",
                                SupportRouting.TO_FALLBACK, "fallback"))

                .addEdge("clarify", "respond")

                .addConditionalEdges("resolve_order",
                        edge_async(SupportRouting::fromResolveOrder),
                        Map.of(
                                SupportRouting.TO_TRACKING, "tracking",
                                SupportRouting.TO_CANCEL_CHECK, "cancel_check",
                                SupportRouting.TO_RETURN_CHECK, "return_check",
                                SupportRouting.TO_SUPPORT, "support",
                                SupportRouting.TO_ASK_USER, "respond"))

                .addConditionalEdges("cancel_check",
                        edge_async(SupportRouting::fromCancelCheck),
                        Map.of(
                                SupportRouting.TO_CANCEL_EXEC, "cancel_exec",
                                SupportRouting.TO_ASK_USER, "respond"))

                .addConditionalEdges("return_check",
                        edge_async(SupportRouting::fromReturnCheck),
                        Map.of(
                                SupportRouting.TO_RETURN_EXEC, "return_exec",
                                SupportRouting.TO_ASK_USER, "respond"))

                .addEdge("tracking", "respond")
                .addEdge("cancel_exec", "respond")
                .addEdge("return_exec", "respond")
                .addEdge("support", "respond")
                .addEdge("tickets", "respond")
                .addEdge("faq", "respond")
                .addEdge("chatty", "respond")
                .addEdge("fallback", "respond")
                .addEdge("respond", END);
    }
}
