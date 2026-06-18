package com.example.httpreading.controller;

import com.example.httpreading.api.CommonResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileOverviewResponse;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateRequest;
import com.example.httpreading.dto.profile.ProfileDtos.ProfileUpdateResponse;
import com.example.httpreading.service.profile.ProfileUpdateService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {
    private final ProfileUpdateService profileUpdateService;

    public ProfileController(ProfileUpdateService profileUpdateService) {
        this.profileUpdateService = profileUpdateService;
    }

    @PostMapping("/update")
    public CommonResponse<ProfileUpdateResponse> update(@RequestBody(required = false) ProfileUpdateRequest request) {
        return CommonResponse.success(profileUpdateService.updateProfileManually(
            request == null ? new ProfileUpdateRequest(null, null, null, null, null, null) : request));
    }

    @GetMapping
    public CommonResponse<ProfileOverviewResponse> overview(@RequestParam(required = false) String userId,
                                                            @RequestParam(required = false) String sessionId) {
        return CommonResponse.success(profileUpdateService.overview(userId, sessionId));
    }
}
