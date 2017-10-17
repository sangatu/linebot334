/*
 * Copyright 2016 LINE Corporation
 *
 * LINE Corporation licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.example.bot.spring;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
//////add
import java.util.Calendar;
import java.text.SimpleDateFormat;
//////
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import com.linecorp.bot.model.action.DatetimePickerAction;
import com.linecorp.bot.model.message.template.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import com.google.common.io.ByteStreams;

import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.action.PostbackAction;
import com.linecorp.bot.model.action.URIAction;
import com.linecorp.bot.model.event.BeaconEvent;
import com.linecorp.bot.model.event.Event;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.JoinEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.PostbackEvent;
import com.linecorp.bot.model.event.UnfollowEvent;
import com.linecorp.bot.model.event.message.AudioMessageContent;
import com.linecorp.bot.model.event.message.ImageMessageContent;
import com.linecorp.bot.model.event.message.LocationMessageContent;
import com.linecorp.bot.model.event.message.StickerMessageContent;
import com.linecorp.bot.model.event.message.TextMessageContent;
import com.linecorp.bot.model.event.message.VideoMessageContent;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.message.AudioMessage;
import com.linecorp.bot.model.message.ImageMessage;
import com.linecorp.bot.model.message.ImagemapMessage;
import com.linecorp.bot.model.message.LocationMessage;
import com.linecorp.bot.model.message.Message;
import com.linecorp.bot.model.message.StickerMessage;
import com.linecorp.bot.model.message.TemplateMessage;
import com.linecorp.bot.model.message.TextMessage;
import com.linecorp.bot.model.message.VideoMessage;
import com.linecorp.bot.model.message.imagemap.ImagemapArea;
import com.linecorp.bot.model.message.imagemap.ImagemapBaseSize;
import com.linecorp.bot.model.message.imagemap.MessageImagemapAction;
import com.linecorp.bot.model.message.imagemap.URIImagemapAction;
import com.linecorp.bot.model.response.BotApiResponse;
import com.linecorp.bot.spring.boot.annotation.EventMapping;
import com.linecorp.bot.spring.boot.annotation.LineMessageHandler;

import lombok.NonNull;
import lombok.Value;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@LineMessageHandler
public class KitchenSinkController {
    @Autowired
    private LineMessagingClient lineMessagingClient;

    @EventMapping
    public void handleTextMessageEvent(MessageEvent<TextMessageContent> event) throws Exception {
        TextMessageContent message = event.getMessage();
        handleTextContent(event.getReplyToken(), event, message);
    }

    @EventMapping
    public void handleStickerMessageEvent(MessageEvent<StickerMessageContent> event) {
        handleSticker(event.getReplyToken(), event.getMessage());
    }

    @EventMapping
    public void handleLocationMessageEvent(MessageEvent<LocationMessageContent> event) {
        LocationMessageContent locationMessage = event.getMessage();
        reply(event.getReplyToken(), new LocationMessage(
                locationMessage.getTitle(),
                locationMessage.getAddress(),
                locationMessage.getLatitude(),
                locationMessage.getLongitude()
        ));
    }

    @EventMapping
    public void handleImageMessageEvent(MessageEvent<ImageMessageContent> event) throws IOException {
        // You need to install ImageMagick
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent jpg = saveContent("jpg", responseBody);
                    DownloadedContent previewImg = createTempFile("jpg");
                    system(
                            "convert",
                            "-resize", "240x",
                            jpg.path.toString(),
                            previewImg.path.toString());
                    reply(((MessageEvent) event).getReplyToken(),
                          new ImageMessage(jpg.getUri(), jpg.getUri()));
                });
    }

    @EventMapping
    public void handleAudioMessageEvent(MessageEvent<AudioMessageContent> event) throws IOException {
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent mp4 = saveContent("mp4", responseBody);
                    reply(event.getReplyToken(), new AudioMessage(mp4.getUri(), 100));
                });
    }

    @EventMapping
    public void handleVideoMessageEvent(MessageEvent<VideoMessageContent> event) throws IOException {
        // You need to install ffmpeg and ImageMagick.
        handleHeavyContent(
                event.getReplyToken(),
                event.getMessage().getId(),
                responseBody -> {
                    DownloadedContent mp4 = saveContent("mp4", responseBody);
                    DownloadedContent previewImg = createTempFile("jpg");
                    system("convert",
                           mp4.path + "[0]",
                           previewImg.path.toString());
                    reply(((MessageEvent) event).getReplyToken(),
                          new VideoMessage(mp4.getUri(), previewImg.uri));
                });
    }

    @EventMapping
    public void handleUnfollowEvent(UnfollowEvent event) {
        log.info("unfollowed this bot: {}", event);
    }

    @EventMapping
    public void handleFollowEvent(FollowEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got followed event");
    }

    @EventMapping
    public void handleJoinEvent(JoinEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Joined " + event.getSource());
    }

    @EventMapping
    public void handlePostbackEvent(PostbackEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got postback data " + event.getPostbackContent().getData() + ", param " + event.getPostbackContent().getParams().toString());
    }

    @EventMapping
    public void handleBeaconEvent(BeaconEvent event) {
        String replyToken = event.getReplyToken();
        this.replyText(replyToken, "Got beacon message " + event.getBeacon().getHwid());
    }

    @EventMapping
    public void handleOtherEvent(Event event) {
        log.info("Received message(Ignored): {}", event);
    }

    private void reply(@NonNull String replyToken, @NonNull Message message) {
        reply(replyToken, Collections.singletonList(message));
    }

    private void reply(@NonNull String replyToken, @NonNull List<Message> messages) {
        try {
            BotApiResponse apiResponse = lineMessagingClient
                    .replyMessage(new ReplyMessage(replyToken, messages))
                    .get();
            log.info("Sent messages: {}", apiResponse);
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void replyText(@NonNull String replyToken, @NonNull String message) {
        if (replyToken.isEmpty()) {
            throw new IllegalArgumentException("replyToken must not be empty");
        }
        if (message.length() > 1000) {
            message = message.substring(0, 1000 - 2) + "……";
        }
        this.reply(replyToken, new TextMessage(message));
    }

    private void handleHeavyContent(String replyToken, String messageId,
                                    Consumer<MessageContentResponse> messageConsumer) {
        final MessageContentResponse response;
        try {
            response = lineMessagingClient.getMessageContent(messageId)
                                          .get();
        } catch (InterruptedException | ExecutionException e) {
            reply(replyToken, new TextMessage("Cannot get image: " + e.getMessage()));
            throw new RuntimeException(e);
        }
        messageConsumer.accept(response);
    }

    private void handleSticker(String replyToken, StickerMessageContent content) {
        reply(replyToken, new StickerMessage(
                content.getPackageId(), content.getStickerId())
        );
    }
/////自働応答
    private void handleTextContent(String replyToken, Event event, TextMessageContent content)
            throws Exception {
        String text = content.getText();

        log.info("Got text message from {}: {}", replyToken, text);
        switch (text) {
            case "profile": {
                String userId = event.getSource().getUserId();
                if (userId != null) {
                    lineMessagingClient
                            .getProfile(userId)
                            .whenComplete((profile, throwable) -> {
                                if (throwable != null) {
                                    this.replyText(replyToken, throwable.getMessage());
                                    return;
                                }

                                this.reply(
                                        replyToken,
                                        Arrays.asList(new TextMessage(
                                                              "Display name: " + profile.getDisplayName()+"さん"),
                                                      new TextMessage("Status message: "
                                                                      + profile.getStatusMessage()))
                                );

                            });
                } else {
                    this.replyText(replyToken, "Bot can't use profile API without user ID");
                }
                break;
            }
            ///////////////編集//////////////////////////////////////////////////////////////////////
            case "目安箱":{
            	this.replyText(replyToken, "いつもご利用ありがとうございます。\nご意見などがございましたらこちらでお願いします。\n072-368-2222");
            	break;
            }
            case "bye": {
                Source source = event.getSource();
                if (source instanceof GroupSource) {
                    this.replyText(replyToken, "Leaving group");
                    lineMessagingClient.leaveGroup(((GroupSource) source).getGroupId()).get();
                } else if (source instanceof RoomSource) {
                    this.replyText(replyToken, "Leaving room");
                    lineMessagingClient.leaveRoom(((RoomSource) source).getRoomId()).get();
                } else {
                    this.replyText(replyToken, "Bot can't leave from 1:1 chat");
                }
                break;
            }

            case "訪問予約": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "電話とネットのどちらで予約をしますか？",
                new MessageAction("電話予約", "電話で予約する"),
                new MessageAction("ネット予約", "ネットで予約する")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "電話で予約する":{
            	this.replyText(replyToken, "072-368-2222");
            	break;
            }
            case "ネットで予約する":{
            	this.replyText(replyToken,"http://www.ionkesho.jp/");
            	break;
            }
            case "アドバイス": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q1頬は脂っぽいですか？",
                new MessageAction("Yes", "Q1.Yes"),
                new MessageAction("No", "Q1.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q1.Yes": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q2A頬に潤い(みずみずしさ)がありますか？",
                new MessageAction("Yes", "Q2A.Yes"),
                new MessageAction("No", "Q2A.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q2A.Yes":{
            	this.replyText(replyToken, "『あなたの肌は脂性肌タイプです』\n皮脂も水分も多めの状態で潤いはあるけどべたつきやすい肌。\n毛孔が大きく、キメがやや粗い。");
            	break;
            }
            case "Q2A.No": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q3A頬はかさつきやすいですか？",
                new MessageAction("Yes", "Q3A.Yes"),
                new MessageAction("No", "Q3A.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q3A.No":{
            	this.replyText(replyToken, "『あなたの肌は脂性肌タイプです』\n皮脂も水分も多めの状態で潤いはあるけどべたつきやすい肌。\n毛孔が大きく、キメがやや粗い。");
            	break;
            }
            case "Q3A.Yes":{
            	this.replyText(replyToken, "『あなたの肌は混合肌タイプです』\n皮脂は多いのに水分が府相している状態。脂っぽいのにかさつきがちな肌。\nニキビになりやすい。");
            	break;
            }

            case "Q1.No": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q2B洗顔後、お手入れをしないでいると頬が突っ張りますか？",
                new MessageAction("Yes", "Q2B.Yes"),
                new MessageAction("No", "Q2B.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q2B.Yes": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q3B頬がかさつきやすいですか？",
                new MessageAction("Yes", "Q3B.Yes"),
                new MessageAction("No", "Q3B.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q3B.Yes":{
            	this.replyText(replyToken, "『あなたの肌は乾燥肌タイプです』\n皮脂が少なく水分も不足している状態で、かさつきやすく、乾燥している肌。\n肌荒れの状態でしわもできやすい。");
            	break;
            }
            case "Q3B.No":{
            	this.replyText(replyToken, "『あなたの肌は健康肌タイプです』\n皮脂が少ないため、水分は多めで潤いのある肌。\n季節や環境の変化により、脂っぽくなったりかさついたり変化しがちである。\n角質細胞層が良い状態でしっとりしている。");
            	break;
            }
            case "Q2B.No": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q3C肌にしっとり感がありますか？",
                new MessageAction("Yes", "Q3C.Yes"),
                new MessageAction("No", "Q3C.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            case "Q3C.Yes":{
            	this.replyText(replyToken, "『あなたの肌は健康肌タイプです』\n皮脂が少ないため、水分は多めで潤いのある肌。\n季節や環境の変化により、脂っぽくなったりかさついたり変化しがちである。\n角質細胞層が良い状態でしっとりしている。");
            	break;
            }
            case "Q3C.No": {
                ConfirmTemplate confirmTemplate = new ConfirmTemplate(
                "Q3B頬がかさつきやすいですか？",
                new MessageAction("Yes", "Q3B.Yes"),
                new MessageAction("No", "Q3B.No")
                 );
               TemplateMessage templateMessage = new TemplateMessage("Confirm alt text", confirmTemplate);
               this.reply(replyToken, templateMessage);
               break;
            }
            //////////////////ここまで/////////////////////////////////////////
            //////////////////smmrstart/////////////////////////////////////
            case "商品情報":{
                String imageUrl = createUri("/static/Products/main-products.png");
                ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                        imageUrl,
                        "商品情報選択",
                        "どの条件で探しますか？",
                        Arrays.asList(
                                new MessageAction("クリーム・美容液・洗顔・メイクアップ",
                                                   "クリーム・美容液・洗顔・メイクアップ"),
                                new MessageAction("ローション・ヘアケア・健康食品",
                                        "ローション・ヘアケア・健康食品"),
                                new MessageAction("ニキビ・乾燥肌・年齢肌",
                                        "ニキビ・乾燥肌・年齢肌"),
                                new MessageAction("ハリ・脂性肌・毛穴の汚れ",
                                                  "ハリ・脂性肌・毛穴の汚れ")
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Button alt text", buttonsTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }

            case "クリーム・美容液・洗顔・メイクアップ":{
            	 String imageUrl = createUri("/static/Products/Category/cream/maberasu.png");
            	 String imgLiquidFoundation = createUri("/static/Products/Category/liquidfoundation/success.png");
            	 String imgBodyCare = createUri("/static/Products/Category/bodycare/ionsoap.png");
            	 String imgMakeUp = createUri("/static/Products/Category/makeup/lipcream.png");

                 ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                         Arrays.asList(
                                 new ImageCarouselColumn(imageUrl,
                                         new URIAction("クリーム",
                                                 "http://www.ionkesho.jp/products/category/cream.html")
                                 ),
                                 new ImageCarouselColumn(imgLiquidFoundation,
                                         new URIAction("美容液",
                                                 "http://www.ionkesho.jp/products/category/liquidfoundation.html")
                                 ),
                                 new ImageCarouselColumn(imgBodyCare,
                                         new URIAction("ボディケア・洗顔",
                                                 "http://www.ionkesho.jp/products/category/bodycare.html")
                                 ),
                                 new ImageCarouselColumn(imgMakeUp,
                                         new URIAction("メイクアップ",
                                                 "http://www.ionkesho.jp/products/category/makeup.html")
                                 )
                         ));
                 TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                 this.reply(replyToken,templateMessage);
                 break;
            }

            //カテゴリ2段目
            case "ローション・ヘアケア・健康食品":{
           	 String imageUrl = createUri("/static/Products/Category/lotion/highlotion.png");
           	 String imgHairCare = createUri("/static/Products/Category/haircare/supershampoo-a.png");
           	 String imgHealthFood = createUri("/static/Products/Category/healthfood/kireinoekisu.png");

                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(imageUrl,
                                        new URIAction("ローション",
                                                "http://www.ionkesho.jp/products/category/lotion.html")
                                ),

                                new ImageCarouselColumn(imgHairCare,
                                        new URIAction("ヘアケア",
                                                "http://www.ionkesho.jp/products/category/haircare.html")
                                ),

                                new ImageCarouselColumn(imgHealthFood,
                                        new URIAction("健康食品",
                                                "http://www.ionkesho.jp/products/category/healthfood.html")
                                )
                        ));

                TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
           }

            case "ニキビ・乾燥肌・年齢肌":{
           	 String imageUrl = createUri("/static/Products/Skin/acne/creamsoap.png");
           	 String imgSkinAge = createUri("/static/Products/Skin/age/lamirumu.png");
           	 String imgSkinDry = createUri("/static/Products/Skin/dry/newroyal.png");
                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(imageUrl,
                                        new URIAction("ニキビ",
                                                "http://www.ionkesho.jp/products/suffering/skincare01.html")
                                ),
                                new ImageCarouselColumn(imgSkinAge,
                                        new URIAction("年齢肌",
                                                "http://www.ionkesho.jp/products/suffering/skincare02.html")
                                ),
                                new ImageCarouselColumn(imgSkinDry,
                                        new URIAction("乾燥肌",
                                                "http://www.ionkesho.jp/products/suffering/skincare03.html")
                                )
                        ));
                TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
           }

            //お悩み2段目
            case "ハリ・脂性肌・毛穴の汚れ":{
              	 String imageUrl = createUri("/static/Products/Skin/resilient/highlotion.png");
              	 String imgSkinOily = createUri("/static/Products/Skin/oily/newgold.png");
              	 String imgPores = createUri("/static/Products/Skin/success.png");

                   ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                           Arrays.asList(
                                   new ImageCarouselColumn(imageUrl,
                                           new URIAction("ハリがない",
                                                   "http://www.ionkesho.jp/products/suffering/skincare04.html")
                                   ),
                                   new ImageCarouselColumn(imgSkinOily,
                                           new URIAction("脂性肌",
                                                   "http://www.ionkesho.jp/products/suffering/skincare05.html")
                                   ),
                                   new ImageCarouselColumn(imgPores,
                                           new URIAction("毛穴の汚れ",
                                                   "http://www.ionkesho.jp/products/suffering/skincare06.html")
                                   )
                           ));
                   TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                   this.reply(replyToken, templateMessage);
                   break;
              }

            case "ポイント確認":{
            	String userId = event.getSource().getUserId();
                if (userId != null) {
                    lineMessagingClient
                            .getProfile(userId)
                            .whenComplete((profile, throwable) -> {
                                if (throwable != null) {
                                    this.replyText(replyToken, throwable.getMessage());
                                    return;
                                }

                                this.reply(
                                        replyToken,
                                        Arrays.asList(new TextMessage(
                                                              profile.getDisplayName() + "さんのポイントは53万ポイントです"))
                                );

                            });
                } else {
                    this.replyText(replyToken, "Bot can't use profile API without user ID");
                }
            	break;
            }

            case "訪問日時を教えて":{
            	String userId = event.getSource().getUserId();

                Calendar today = Calendar.getInstance();

                SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日kk時mm分");

                if (userId != null) {
                    lineMessagingClient
                            .getProfile(userId)
                            .whenComplete((profile, throwable) -> {
                                if (throwable != null) {
                                    this.replyText(replyToken, throwable.getMessage());
                                    return;
                                }

                                this.reply(
                                        replyToken,
                                        Arrays.asList(new TextMessage(
                                                              profile.getDisplayName() + "さんのご自宅には"+sdf.format(today.getTime())+"に訪問させていただきます"))
                                );

                            });
                } else {
                    this.replyText(replyToken, "Bot can't use profile API without user ID");
                }
            	break;
            }


           ///////////////////smmrend/////////////////////////////////////////
            case "buttons": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                        imageUrl,
                        "My button sample",
                        "Hello, my button",
                        Arrays.asList(
                                new URIAction("Go to line.me",
                                              "https://line.me"),
                                new PostbackAction("Say hello1",
                                                   "hello こんにちは"),
                                new PostbackAction("言 hello2",
                                                   "hello こんにちは",
                                                   "hello こんにちは"),
                                new MessageAction("Say message",
                                                  "ちくわ大明神")
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Button alt text", buttonsTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }

            case "だれだこいつ": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                CarouselTemplate carouselTemplate = new CarouselTemplate(
                        Arrays.asList(
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new URIAction("Go to line.me",
                                                      "https://line.me"),
                                        new URIAction("Go to line.me",
                                                "https://line.me"),
                                        new PostbackAction("Say hello1",
                                                           "hello こんにちは")
                                )),
                                new CarouselColumn(imageUrl, "hoge", "fuga", Arrays.asList(
                                        new PostbackAction("言 hello2",
                                                           "hello こんにちは",
                                                           "hello こんにちは"),
                                        new PostbackAction("言 hello2",
                                                "hello こんにちは",
                                                "hello こんにちは"),
                                        new MessageAction("Say message",
                                                          "ちくわ大明神")
                                )),
                                new CarouselColumn(imageUrl, "Datetime Picker", "Please select a date, time or datetime", Arrays.asList(
                                        new DatetimePickerAction("Datetime",
                                                "action=sel",
                                                "datetime",
                                                "2017-06-18T06:15",
                                                "2100-12-31T23:59",
                                                "1900-01-01T00:00"),
                                        new DatetimePickerAction("Date",
                                                "action=sel&only=date",
                                                "date",
                                                "2017-06-18",
                                                "2100-12-31",
                                                "1900-01-01"),
                                        new DatetimePickerAction("Time",
                                                "action=sel&only=time",
                                                "time",
                                                "06:15",
                                                "23:59",
                                                "00:00")
                                ))
                        ));
                TemplateMessage templateMessage = new TemplateMessage("Carousel alt text", carouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "image_carousel": {
                String imageUrl = createUri("/static/buttons/1040.jpg");
                ImageCarouselTemplate imageCarouselTemplate = new ImageCarouselTemplate(
                        Arrays.asList(
                                new ImageCarouselColumn(imageUrl,
                                        new URIAction("Goto line.me",
                                                "https://line.me")
                                ),
                                new ImageCarouselColumn(imageUrl,
                                        new MessageAction("Say message",
                                                "Rice=米")
                                ),
                                new ImageCarouselColumn(imageUrl,
                                        new PostbackAction("言 hello2",
                                                "hello こんにちは",
                                                "hello こんにちは")
                                )
                        ));
                TemplateMessage templateMessage = new TemplateMessage("ImageCarousel alt text", imageCarouselTemplate);
                this.reply(replyToken, templateMessage);
                break;
            }
            case "imagemap":
                this.reply(replyToken, new ImagemapMessage(
                        createUri("/static/rich"),
                        "This is alt text",
                        new ImagemapBaseSize(1040, 1040),
                        Arrays.asList(
                                new URIImagemapAction(
                                        "https://store.line.me/family/manga/en",
                                        new ImagemapArea(
                                                0, 0, 520, 520
                                        )
                                ),
                                new URIImagemapAction(
                                        "https://store.line.me/family/music/en",
                                        new ImagemapArea(
                                                520, 0, 520, 520
                                        )
                                ),
                                new URIImagemapAction(
                                        "https://store.line.me/family/play/en",
                                        new ImagemapArea(
                                                0, 520, 520, 520
                                        )
                                ),
                                new MessageImagemapAction(
                                        "URANAI!",
                                        new ImagemapArea(
                                                520, 520, 520, 520
                                        )
                                )
                        )
                ));
                break;
            default:
                log.info("Returns echo message {}: {}", replyToken, text);
                this.replyText(
                        replyToken,
                        text
                );
                break;
        }
    }

    private static String createUri(String path) {
        return ServletUriComponentsBuilder.fromCurrentContextPath()
                                          .path(path).build()
                                          .toUriString();
    }

    private void system(String... args) {
        ProcessBuilder processBuilder = new ProcessBuilder(args);
        try {
            Process start = processBuilder.start();
            int i = start.waitFor();
            log.info("result: {} =>  {}", Arrays.toString(args), i);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            log.info("Interrupted", e);
            Thread.currentThread().interrupt();
        }
    }

    private static DownloadedContent saveContent(String ext, MessageContentResponse responseBody) {
        log.info("Got content-type: {}", responseBody);

        DownloadedContent tempFile = createTempFile(ext);
        try (OutputStream outputStream = Files.newOutputStream(tempFile.path)) {
            ByteStreams.copy(responseBody.getStream(), outputStream);
            log.info("Saved {}: {}", ext, tempFile);
            return tempFile;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static DownloadedContent createTempFile(String ext) {
        String fileName = LocalDateTime.now().toString() + '-' + UUID.randomUUID().toString() + '.' + ext;
        Path tempFile = KitchenSinkApplication.downloadedContentDir.resolve(fileName);
        tempFile.toFile().deleteOnExit();
        return new DownloadedContent(
                tempFile,
                createUri("/downloaded/" + tempFile.getFileName()));
    }

    @Value
    public static class DownloadedContent {
        Path path;
        String uri;
    }
}
