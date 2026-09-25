package com.steven.assets.srpp;

import org.springframework.data.jpa.repository.JpaRepository;

/** 唯讀使用：registry 登錄只由離線 SQL（scripts/srpp-policy-register.rb）進行。 */
public interface SrppPolicyRegistryRepository extends JpaRepository<SrppPolicyRegistryEntry, String> {
}
