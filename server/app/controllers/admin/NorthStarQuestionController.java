package controllers.admin;

import static com.google.common.base.Preconditions.checkNotNull;

import auth.CiviFormProfile;
import auth.ProfileUtils;
import com.google.common.collect.ImmutableList;
import controllers.CiviFormController;
import java.util.Optional;
import javax.inject.Inject;
import org.pac4j.play.java.Secure;
import play.i18n.Lang;
import play.i18n.Messages;
import play.i18n.MessagesApi;
import play.mvc.Http;
import play.mvc.Http.Request;
import play.mvc.Result;
import repository.VersionRepository;
import services.applicant.ApplicantPersonalInfo;
import services.applicant.ApplicantPersonalInfo.Representation;
import services.question.exceptions.InvalidQuestionTypeException;
import services.question.types.QuestionType;
import views.admin.questions.NorthStarQuestionPreview;

/** Controller for rendering inputs for questions. */
public final class NorthStarQuestionController extends CiviFormController {

  // private final TemplateEngine templateEngine;
  // private final ThymeleafModule.PlayThymeleafContextFactory playThymeleafContextFactory;
  private final NorthStarQuestionPreview northStarQuestionPreview;
  private final Messages messages;

  @Inject
  public NorthStarQuestionController(
      ProfileUtils profileUtils,
      VersionRepository versionRepository,
      // TemplateEngine templateEngine,
      // ThymeleafModule.PlayThymeleafContextFactory playThymeleafContextFactory,
      NorthStarQuestionPreview northStarQuestionPreview,
      MessagesApi messagesApi) {
    super(profileUtils, versionRepository);
    // this.templateEngine = checkNotNull(templateEngine);
    // this.playThymeleafContextFactory = checkNotNull(playThymeleafContextFactory);
    this.northStarQuestionPreview = checkNotNull(northStarQuestionPreview);
    this.messages = messagesApi.preferred(ImmutableList.of(Lang.defaultLang()));
  }

  @Secure
  public Result sampleQuestion(Request request, String questionType) {
    Representation representation = Representation.builder().build();
    ApplicantPersonalInfo api = ApplicantPersonalInfo.ofGuestUser(representation);
    Optional<CiviFormProfile> profile = profileUtils.currentUserProfile(request);

    QuestionType questionTypeEnum;
    try {
      questionTypeEnum = QuestionType.of(questionType);
    } catch (InvalidQuestionTypeException e) {
      System.out.println("Invalid question type: " + questionType);
      return badRequest("Invalid question type: " + questionType);
    }

    NorthStarQuestionPreview.Params params =
        NorthStarQuestionPreview.Params.builder()
            .setRequest(request)
            .setApplicantId(0l)
            .setApplicantPersonalInfo(api)
            .setProfile(profile.orElse(null))
            .setType(questionTypeEnum)
            .setMessages(messages)
            .build();

    String content = northStarQuestionPreview.render(params);

    return ok(content).as(Http.MimeTypes.HTML);
  }
}
