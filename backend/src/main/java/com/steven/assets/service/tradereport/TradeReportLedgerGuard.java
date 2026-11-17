package com.steven.assets.service.tradereport;
import com.steven.assets.model.*;
import com.steven.assets.repository.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.time.LocalDate;
/** Shared lock ordering for every ledger writer that can collide with an email import. */
@Service @RequiredArgsConstructor
public class TradeReportLedgerGuard {
 private final AppUserRepository users;
 private final AssetTransactionRepository transactions;
 public void lock(Long owner){
   if(owner==null)throw new TradeReportProblem("AUTHENTICATION_REQUIRED",403);
   AppUser user=users.findByIdForUpdate(owner).orElseThrow(()->new TradeReportProblem("OWNER_UNAVAILABLE",403));
   if(!AppUser.STATUS_ACTIVE.equals(user.getStatus()))throw new TradeReportProblem("OWNER_UNAVAILABLE",403);
 }
 public void rejectEmailCollision(Long owner,LocalDate day,String code,String side,String market,String channel,Long exceptId){
   if(!"台股".equals(market)||!"富邦證券".equals(channel)||day==null||code==null||side==null)return;
   boolean collision=transactions.findReportCandidates(owner,day,code,side,channel).stream()
     .anyMatch(t->"FUBON_EMAIL".equals(t.getSource())&&!java.util.Objects.equals(t.getId(),exceptId));
   if(collision)throw new TradeReportProblem("POSSIBLE_DUPLICATE",409);
 }
}
