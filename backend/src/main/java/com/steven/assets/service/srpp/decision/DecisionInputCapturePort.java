package com.steven.assets.service.srpp.decision;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import static com.steven.assets.service.srpp.decision.DecisionFacts.*;
public interface DecisionInputCapturePort {
    record Frozen(Input facts,ObjectNode input,ObjectNode sourceVector,long assetSnapshotId,String assetGeneratedAt) {public Frozen{input=input.deepCopy();sourceVector=sourceVector.deepCopy();} public ObjectNode input(){return input.deepCopy();} public ObjectNode sourceVector(){return sourceVector.deepCopy();}}
    Frozen capture(long ownerId,DecisionRequest request,List<ObjectNode> priorReservations);
}
