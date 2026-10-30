package cc.hrva.urlshortener.repository;

import cc.hrva.urlshortener.model.AppSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppSettingRepository extends JpaRepository<AppSetting, String> {

}
