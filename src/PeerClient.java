package snowblossom;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import snowblossom.proto.PeerServiceGrpc.PeerServiceStub;
import snowblossom.proto.PeerServiceGrpc;

import snowblossom.proto.PeerMessage;
import snowblossom.proto.PeerInfo;


public class PeerClient
{
  public PeerClient(SnowBlossomNode node, PeerInfo info)
  {
    ManagedChannel channel = ManagedChannelBuilder
      .forAddress(info.getHost(), info.getPort())
      .usePlaintext(true)
      .build();

    PeerServiceStub asyncStub = PeerServiceGrpc.newStub(channel);

    PeerLink link = new PeerLink(node, PeerUtil.getString(info));
    StreamObserver<PeerMessage> sink = asyncStub.subscribePeering(link);
    link.setSink(sink);
    link.setChannel(channel);

    node.getPeerage().register(link);

  }


}
