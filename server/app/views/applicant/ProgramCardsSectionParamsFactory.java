package views.applicant;

import static com.google.common.base.Preconditions.checkNotNull;
import static services.applicant.ApplicantPersonalInfo.ApplicantType.GUEST;

import auth.CiviFormProfile;
import auth.ProfileUtils;
import com.google.auto.value.AutoValue;
import com.google.common.collect.ImmutableList;
import com.google.inject.Inject;
import controllers.applicant.ApplicantRoutes;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import play.i18n.Messages;
import play.mvc.Http.Request;
import services.MessageKey;
import services.applicant.ApplicantPersonalInfo;
import services.applicant.ApplicantService.ApplicantProgramData;
import services.cloud.PublicStorageClient;
import services.program.ProgramDefinition;
import views.ProgramImageUtils;
import views.components.Modal;

/**
 * Factory for creating parameter info for applicant program card sections.
 *
 * <p>The template which uses this is defined in ProgramCardsSectionFragment.html
 */
public final class ProgramCardsSectionParamsFactory {
  private final ApplicantRoutes applicantRoutes;
  private final ProfileUtils profileUtils;
  private final PublicStorageClient publicStorageClient;

  /** Enumerates the homepage section types, which may have different card components or styles. */
  public enum SectionType {
    MY_APPLICATIONS,
    COMMON_INTAKE,
    STANDARD;
  }

  @Inject
  public ProgramCardsSectionParamsFactory(
      ApplicantRoutes applicantRoutes,
      ProfileUtils profileUtils,
      PublicStorageClient publicStorageClient) {
    this.applicantRoutes = checkNotNull(applicantRoutes);
    this.profileUtils = checkNotNull(profileUtils);
    this.publicStorageClient = checkNotNull(publicStorageClient);
  }

  /**
   * Returns ProgramSectionParams containing a list of cards for the provided list of program data.
   */
  public ProgramSectionParams getSection(
      Request request,
      Messages messages,
      Optional<MessageKey> title,
      MessageKey buttonText,
      ImmutableList<ApplicantProgramData> programData,
      Locale preferredLocale,
      Optional<CiviFormProfile> profile,
      Optional<Long> applicantId,
      ApplicantPersonalInfo personalInfo,
      SectionType sectionType) {

    List<ProgramCardParams> cards =
        getCards(
            request,
            messages,
            buttonText,
            programData,
            preferredLocale,
            profile,
            applicantId,
            personalInfo);

    ProgramSectionParams.Builder sectionBuilder = ProgramSectionParams.builder().setCards(cards);

    if (title.isPresent()) {
      sectionBuilder.setTitle(messages.at(title.get().getKeyName(), cards.size()));
      sectionBuilder.setId(Modal.randomModalId());
    }

    sectionBuilder.setSectionType(sectionType);

    return sectionBuilder.build();
  }

  /** Returns a list of ProgramCardParams corresponding with the provided list of program data. */
  public ImmutableList<ProgramCardParams> getCards(
      Request request,
      Messages messages,
      MessageKey buttonText,
      ImmutableList<ApplicantProgramData> programData,
      Locale preferredLocale,
      Optional<CiviFormProfile> profile,
      Optional<Long> applicantId,
      ApplicantPersonalInfo personalInfo) {
    ImmutableList.Builder<ProgramCardParams> cardsListBuilder = ImmutableList.builder();

    for (ApplicantProgramData programDatum : programData) {
      cardsListBuilder.add(
          getCard(
              request,
              messages,
              buttonText,
              programDatum,
              preferredLocale,
              profile,
              applicantId,
              personalInfo));
    }

    return cardsListBuilder.build();
  }

  public ProgramCardParams getCard(
      Request request,
      Messages messages,
      MessageKey buttonText,
      ApplicantProgramData programDatum,
      Locale preferredLocale,
      Optional<CiviFormProfile> profile,
      Optional<Long> applicantId,
      ApplicantPersonalInfo personalInfo) {
    ProgramCardParams.Builder cardBuilder = ProgramCardParams.builder();
    ProgramDefinition program = programDatum.program();

    // This works for logged-in and logged-out applicants
    String actionUrl = applicantRoutes.edit(program.id()).url();
    if (profile.isPresent() && applicantId.isPresent()) {
      // TIs need to specify applicant ID
      actionUrl = applicantRoutes.edit(profile.get(), applicantId.get(), program.id()).url();
    }

    boolean isGuest = personalInfo.getType() == GUEST;

    ImmutableList.Builder<String> categoriesBuilder = ImmutableList.builder();
    categoriesBuilder.addAll(
        program.categories().stream()
            .map(c -> c.getLocalizedName().getOrDefault(preferredLocale))
            .collect(ImmutableList.toImmutableList()));

    cardBuilder
        .setTitle(program.localizedName().getOrDefault(preferredLocale))
        .setBody(program.localizedDescription().getOrDefault(preferredLocale))
        .setDetailsUrl(
            controllers.applicant.routes.ApplicantProgramsController.show(program.adminName())
                .url())
        .setActionUrl(actionUrl)
        .setIsGuest(isGuest)
        .setCategories(categoriesBuilder.build())
        .setActionText(messages.at(buttonText.getKeyName()));

    if (isGuest) {
      cardBuilder.setLoginModalId("login-dialog-" + program.id());
    }

    if (programDatum.latestSubmittedApplicationStatus().isPresent()) {
      cardBuilder.setApplicationStatus(
          programDatum
              .latestSubmittedApplicationStatus()
              .get()
              .localizedStatusText()
              .getOrDefault(preferredLocale));
    }

    if (shouldShowEligibilityTag(programDatum)) {
      boolean isEligible = programDatum.isProgramMaybeEligible().get();
      CiviFormProfile submittingProfile = profileUtils.currentUserProfile(request);
      boolean isTrustedIntermediary = submittingProfile.isTrustedIntermediary();
      MessageKey mayQualifyMessage =
          isTrustedIntermediary ? MessageKey.TAG_MAY_QUALIFY_TI : MessageKey.TAG_MAY_QUALIFY;
      MessageKey mayNotQualifyMessage =
          isTrustedIntermediary
              ? MessageKey.TAG_MAY_NOT_QUALIFY_TI
              : MessageKey.TAG_MAY_NOT_QUALIFY;

      cardBuilder.setEligible(isEligible);
      cardBuilder.setEligibilityMessage(
          messages.at(
              isEligible ? mayQualifyMessage.getKeyName() : mayNotQualifyMessage.getKeyName()));
    }

    Optional<String> fileKey = program.summaryImageFileKey();
    if (fileKey.isPresent()) {
      String imageSourceUrl = publicStorageClient.getPublicDisplayUrl(fileKey.get());
      cardBuilder.setImageSourceUrl(imageSourceUrl);

      String altText = ProgramImageUtils.getProgramImageAltText(program, preferredLocale);
      cardBuilder.setAltText(altText);
    }

    return cardBuilder.build();
  }

  /**
   * If eligibility is gating, the eligibility tag should always show when present. If eligibility
   * is non-gating, the eligibility tag should only show if the user may be eligible.
   */
  private static boolean shouldShowEligibilityTag(ApplicantProgramData programData) {
    if (!programData.isProgramMaybeEligible().isPresent()) {
      return false;
    }

    return programData.program().eligibilityIsGating()
        || programData.isProgramMaybeEligible().get();
  }

  @AutoValue
  public abstract static class ProgramSectionParams {
    public abstract ImmutableList<ProgramCardParams> cards();

    public abstract Optional<String> title();

    public abstract SectionType sectionType();

    /** The id of the section. Only needs to be specified if a title is also specified. */
    public abstract Optional<String> id();

    public static Builder builder() {
      return new AutoValue_ProgramCardsSectionParamsFactory_ProgramSectionParams.Builder();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setCards(List<ProgramCardParams> cards);

      public abstract Builder setTitle(String title);

      public abstract Builder setSectionType(SectionType sectionType);

      public abstract Builder setId(String id);

      public abstract ProgramSectionParams build();
    }
  }

  @AutoValue
  public abstract static class ProgramCardParams {
    public abstract String title();

    public abstract String actionText();

    public abstract String body();

    public abstract String detailsUrl();

    public abstract String actionUrl();

    public abstract boolean isGuest();

    public abstract Optional<String> loginModalId();

    public abstract Optional<Boolean> eligible();

    public abstract Optional<String> eligibilityMessage();

    public abstract Optional<String> applicationStatus();

    public abstract Optional<String> imageSourceUrl();

    public abstract Optional<String> altText();

    public abstract ImmutableList<String> categories();

    public static Builder builder() {
      return new AutoValue_ProgramCardsSectionParamsFactory_ProgramCardParams.Builder();
    }

    public abstract Builder toBuilder();

    @AutoValue.Builder
    public abstract static class Builder {
      public abstract Builder setTitle(String title);

      public abstract Builder setActionText(String actionText);

      public abstract Builder setBody(String body);

      public abstract Builder setDetailsUrl(String detailsUrl);

      public abstract Builder setActionUrl(String actionUrl);

      public abstract Builder setIsGuest(Boolean isGuest);

      public abstract Builder setLoginModalId(String loginModalId);

      public abstract Builder setEligible(Boolean eligible);

      public abstract Builder setEligibilityMessage(String eligibilityMessage);

      public abstract Builder setApplicationStatus(String applicationStatus);

      public abstract Builder setImageSourceUrl(String imageSourceUrl);

      public abstract Builder setAltText(String altText);

      public abstract Builder setCategories(ImmutableList<String> categories);

      public abstract ProgramCardParams build();
    }
  }
}
