package com.openmc.alertmanager.model.discord;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO representing a Discord webhook payload
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DiscordWebhookPayload {
    
    private String content;
    private String username;
    @JsonProperty("avatar_url")
    private String avatarUrl;
    private Boolean tts;
    private List<DiscordEmbed> embeds;
}
