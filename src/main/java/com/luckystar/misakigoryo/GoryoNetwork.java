package com.luckystar.misakigoryo;

import java.util.function.Supplier;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

public final class GoryoNetwork {
    private static final String VERSION = "1";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
        MisakiGoryo.id("goryo"), () -> VERSION, VERSION::equals, VERSION::equals);

    private GoryoNetwork() {}
    public static void register() {
        CHANNEL.registerMessage(0, Toggle.class, (message, buffer) -> {},
            buffer -> new Toggle(), GoryoNetwork::toggle);
        CHANNEL.registerMessage(1, Voice.class, Voice::encode, Voice::decode, GoryoNetwork::voice);
        CHANNEL.registerMessage(2, VoiceEnded.class,(message,buffer)->buffer.writeUtf(message.event,40),
            buffer->new VoiceEnded(buffer.readUtf(40)),GoryoNetwork::voiceEnded);
    }

    public static void toggle() { CHANNEL.sendToServer(new Toggle()); }
    public static void voice(ServerPlayer owner, String event) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY_AND_SELF.with(() -> owner),
            new Voice(owner.getId(), event));
    }
    public static void mute(ServerPlayer owner) { voice(owner, ""); }
    public static void voiceFinished(String event) { CHANNEL.sendToServer(new VoiceEnded(event)); }
    private static void voiceEnded(VoiceEnded message,Supplier<NetworkEvent.Context> context) {
        var ctx=context.get();
        ServerPlayer player=ctx.getSender();
        if(player!=null&&message.event.equals("win")) ctx.enqueueWork(()->GoryoVoice.finished(player,message.event));
        ctx.setPacketHandled(true);
    }

    private static void toggle(Toggle message, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ServerPlayer player = ctx.getSender();
        if (player != null) ctx.enqueueWork(() -> GoryoSpiritEvents.toggle(player));
        ctx.setPacketHandled(true);
    }
    private static void voice(Voice message, Supplier<NetworkEvent.Context> context) {
        var ctx = context.get();
        ctx.enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
            () -> () -> GoryoClient.voice(message.owner, message.event)));
        ctx.setPacketHandled(true);
    }
    private record Toggle() {}
    private record VoiceEnded(String event) {}
    private record Voice(int owner, String event) {
        void encode(FriendlyByteBuf buffer) { buffer.writeVarInt(owner); buffer.writeUtf(event, 40); }
        static Voice decode(FriendlyByteBuf buffer) {
            return new Voice(buffer.readVarInt(), buffer.readUtf(40));
        }
    }
}
