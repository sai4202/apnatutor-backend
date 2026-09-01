package com.apnatutor.settings;

import java.util.List;

import com.apnatutor.settings.domain.PlatformSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PlatformSettingRepository extends JpaRepository<PlatformSetting, String> {

	List<PlatformSetting> findAllByOrderByKeyAsc();
}
