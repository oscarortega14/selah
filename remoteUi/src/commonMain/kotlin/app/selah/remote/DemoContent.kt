package app.selah.remote

// Contenido de la demostración (se genera a mano una vez; no cambia).
// Canciones: letra original escrita para la demostración (sin derechos de terceros).
// Biblia: Reina-Valera 1909 (dominio público, texto de eBible.org), solo algunos capítulos.

internal val DEMO_SONGS: List<String> = listOf(
    """
# Tu luz en mí
BPM: 74

Cuando la noche parece no acabar
Tu voz me llama a despertar
En medio de la tormenta estás
Y en tu palabra encuentro paz

Coro:
Tu luz en mí, tu luz en mí
Brilla más fuerte que el temor
Tu luz en mí, tu luz en mí
Eres mi canto y mi canción

Puente:
No hay sombra que pueda esconder
Lo que tu amor vino a hacer

Final:
Tu luz en mí, por siempre en mí
""",
    """
# Aquí estamos, Señor
BPM: 68

Aquí estamos, Señor, con el corazón abierto
Venimos a adorarte, a cantarte otra vez
Lo que somos te damos, lo que tenemos es tuyo
En tu casa encontramos un lugar para crecer

Coro:
Santo, santo, tu nombre es santo
Digno, digno, de toda gloria
Te alabamos, te bendecimos
Aquí estamos, Señor

Este día es tuyo, cada paso te entregamos
Que tu reino se vea en cada hogar
""",
    """
# Cantaré de tu amor
BPM: 120

Desde que sale el sol hasta que se pone
Cantaré de tu amor, cantaré de tu amor
Tu fidelidad me sigue cada día
Cantaré de tu amor

Coro:
Grande es tu bondad, inmensa tu verdad
Nunca me has dejado, nunca me dejarás
Cantaré de tu amor
""",
    """
# Refugio
BPM: 64

Eres mi refugio y mi fortaleza
Mi pronto auxilio en la tribulación
Aunque la tierra tiemble no temeré
Porque tú estás conmigo

Coro:
En ti confío, en ti espero
Mi roca firme, mi salvación
""",
    """
# Nuevo amanecer
BPM: 128

Tus misericordias son nuevas cada mañana
Un nuevo amanecer, una nueva canción
Lo viejo ya pasó, todo es hecho nuevo
Tu gracia me alcanzó

Coro:
Levántate y resplandece
Que ha venido tu luz
""",
    """
# En tu presencia
BPM: 60

En tu presencia hay plenitud de gozo
Delicias a tu diestra para siempre
Quiero quedarme aquí, a tus pies
Quiero escuchar tu voz otra vez
""",
)

/** Libro → número de capítulos de la Biblia completa (para que el explorador se vea igual). */
internal val DEMO_BIBLE_BOOKS: List<Pair<String, Int>> = listOf(
    "Salmos" to 150,
    "Juan" to 21,
    "Romanos" to 16,
    "1 Corintios" to 16,
    "Filipenses" to 4,
)

/** (libro, capítulo) → versículos. */
internal val DEMO_BIBLE_CHAPTERS: Map<Pair<String, Int>, List<String>> = mapOf(
    ("Salmos" to 23) to listOf(
        "Salmo de David. JEHOVÁ es mi pastor; nada me faltará.",
        "En lugares de delicados pastos me hará yacer: junto á aguas de reposo me pastoreará.",
        "Confortará mi alma; guiaráme por sendas de justicia por amor de su nombre.",
        "Aunque ande en valle de sombra de muerte, no temeré mal alguno; porque tú estarás conmigo: tu vara y tu cayado me infundirán aliento.",
        "Aderezarás mesa delante de mí, en presencia de mis angustiadores: ungiste mi cabeza con aceite: mi copa está rebosando.",
        "Ciertamente el bien y la misericordia me seguirán todos los días de mi vida: y en la casa de Jehová moraré por largos días.",
    ),
    ("Salmos" to 46) to listOf(
        "Al Músico principal: de los hijos de Coré: Salmo sobre Alamoth. DIOS es nuestro amparo y fortaleza, nuestro pronto auxilio en las tribulaciones.",
        "Por tanto no temeremos aunque la tierra sea removida; aunque se traspasen los montes al corazón de la mar.",
        "Bramarán, turbaránse sus aguas; temblarán los montes á causa de su braveza. (Selah.)",
        "Del río sus conductos alegrarán la ciudad de Dios, el santuario de las tiendas del Altísimo.",
        "Dios está en medio de ella; no será conmovida: Dios la ayudará al clarear la mañana.",
        "Bramaron las gentes, titubearon los reinos; dió él su voz, derritióse la tierra.",
        "Jehová de los ejércitos es con nosotros; nuestro refugio es el Dios de Jacob. (Selah.)",
        "Venid, ved las obras de Jehová, que ha puesto asolamientos en la tierra.",
        "Que hace cesar las guerras hasta los fines de la tierra: que quiebra el arco, corta la lanza, y quema los carros en el fuego.",
        "Estad quietos, y conoced que yo soy Dios: ensalzado he de ser entre las gentes, ensalzado seré en la tierra.",
        "Jehová de los ejércitos es con nosotros; nuestro refugio es el Dios de Jacob. (Selah.)",
    ),
    ("Juan" to 3) to listOf(
        "Y HABÍA un hombre de los Fariseos que se llamaba Nicodemo, príncipe de los Judíos.",
        "Este vino á Jesús de noche, y díjole: Rabbí, sabemos que has venido de Dios por maestro; porque nadie puede hacer estas señales que tú haces, si no fuere Dios con él.",
        "Respondió Jesús, y díjole: De cierto, de cierto te digo, que el que no naciere otra vez, no puede ver el reino de Dios.",
        "Dícele Nicodemo: ¿Cómo puede el hombre nacer siendo viejo? ¿puede entrar otra vez en el vientre de su madre, y nacer?",
        "Respondió Jesús: De cierto, de cierto te digo, que el que no naciere de agua y del Espíritu, no puede entrar en el reino de Dios.",
        "Lo que es nacido de la carne, carne es; y lo que es nacido del Espíritu, espíritu es.",
        "No te maravilles de que te dije: Os es necesario nacer otra vez.",
        "El viento de donde quiere sopla, y oyes su sonido; mas ni sabes de dónde viene, ni á dónde vaya: así es todo aquel que es nacido del Espíritu.",
        "Respondió Nicodemo, y díjole: ¿Cómo puede esto hacerse?",
        "Respondió Jesús, y díjole: ¿Tú eres el maestro de Israel, y no sabes esto?",
        "De cierto, de cierto te digo, que lo que sabemos hablamos, y lo que hemos visto, testificamos; y no recibís nuestro testimonio.",
        "Si os he dicho cosas terrenas, y no creéis, ¿cómo creeréis si os dijere las celestiales?",
        "Y nadie subió al cielo, sino el que descendió del cielo, el Hijo del hombre, que está en el cielo.",
        "Y como Moisés levantó la serpiente en el desierto, así es necesario que el Hijo del hombre sea levantado;",
        "Para que todo aquel que en él creyere, no se pierda, sino que tenga vida eterna.",
        "Porque de tal manera amó Dios al mundo, que ha dado á su Hijo unigénito, para que todo aquel que en él cree, no se pierda, mas tenga vida eterna.",
        "Porque no envió Dios á su Hijo al mundo para que condene al mundo, mas para que el mundo sea salvo por él.",
        "El que en él cree, no es condenado; mas el que no cree, ya es condenado, porque no creyó en el nombre del unigénito Hijo de Dios.",
        "Y esta es la condenación: porque la luz vino al mundo, y los hombres amaron más las tinieblas que la luz; porque sus obras eran malas.",
        "Porque todo aquel que hace lo malo, aborrece la luz y no viene á la luz, porque sus obras no sean redargüidas.",
        "Mas el que obra verdad, viene á la luz, para que sus obras sean manifestadas que son hechas en Dios.",
        "Pasado esto, vino Jesús con sus discípulos á la tierra de Judea; y estaba allí con ellos, y bautizaba.",
        "Y bautizaba también Juan en Enón junto á Salim, porque había allí muchas aguas; y venían, y eran bautizados.",
        "Porque Juan no había sido aún puesto en la carcel.",
        "Y hubo cuestión entre los discípulos de Juan y los Judíos acerca de la purificación.",
        "Y vinieron á Juan, y dijéronle: Rabbí, el que estaba contigo de la otra parte del Jordán, del cual tú diste testimonio, he aquí bautiza, y todos vienen á él.",
        "Respondió Juan, y dijo: No puede el hombre recibir algo, si no le fuere dado del cielo.",
        "Vosotros mismos me sois testigos que dije: Yo no soy el Cristo, sino que soy enviado delante de él.",
        "El que tiene la esposa, es el esposo; mas el amigo del esposo, que está en pie y le oye, se goza grandemente de la voz del esposo; así pues, este mi gozo es cumplido.",
        "A él conviene crecer, mas á mí menguar.",
        "El que de arriba viene, sobre todos es: el que es de la tierra, terreno es, y cosas terrenas habla: el que viene del cielo, sobre todos es.",
        "Y lo que vió y oyó, esto testifica: y nadie recibe su testimonio.",
        "El que recibe su testimonio, éste signó que Dios es verdadero.",
        "Porque el que Dios envió, las palabras de Dios habla: porque no da Dios el Espíritu por medida.",
        "El Padre ama al Hijo, y todas las cosas dió en su mano.",
        "El que cree en el Hijo, tiene vida eterna; mas el que es incrédulo al Hijo, no verá la vida, sino que la ira de Dios está sobre él.",
    ),
    ("Romanos" to 5) to listOf(
        "Justificados pues por la fe, tenemos paz para con Dios por medio de nuestro Señor Jesucristo:",
        "Por el cual también tenemos entrada por la fe á esta gracia en la cual estamos [firmes], y nos gloriamos en la esperanza de la gloria de Dios.",
        "Y no sólo esto, mas aun nos gloriamos en las tribulaciones, sabiendo que la tribulación produce paciencia;",
        "Y la paciencia, prueba; y la prueba, esperanza;",
        "Y la esperanza no avergüenza; porque el amor de Dios está derramado en nuestros corazones por el Espíritu Santo que nos es dado.",
        "Porque Cristo, cuando aun éramos flacos, á su tiempo murió por los impíos.",
        "Ciertamente apenas muere alguno por un justo: con todo podrá ser que alguno osara morir por el bueno.",
        "Mas Dios encarece su caridad para con nosotros, porque siendo aún pecadores, Cristo murió por nosotros.",
        "Luego mucho más ahora, justificados en su sangre, por él seremos salvos de la ira.",
        "Porque si siendo enemigos, fuimos reconciliados con Dios por la muerte de su Hijo, mucho más, estando reconciliados, seremos salvos por su vida.",
        "Y no sólo esto, mas aun nos gloriamos en Dios por el Señor nuestro Jesucristo, por el cual hemos ahora recibido la reconciliación.",
        "De consiguiente, [vino la reconciliación por uno], así como el pecado entró en el mundo por un hombre, y por el pecado la muerte, y la muerte así pasó á todos los hombres, pues que todos pecaron.",
        "Porque hasta la ley, el pecado estaba en el mundo; pero no se imputa pecado no habiendo ley.",
        "No obstante, reinó la muerte desde Adam hasta Moisés, aun en los que no pecaron á la manera de la rebelión de Adam; el cual es figura del que había de venir.",
        "Mas no como el delito, tal fué el don: porque si por el delito de aquel uno murieron los muchos, mucho más abundó la gracia de Dios á los muchos, y el don por la gracia de un hombre, Jesucristo.",
        "Ni tampoco de la manera que por un pecado, [así] también el don: porque el juicio á la verdad [vino] de un [pecado] para condenación, mas la gracia [vino] de muchos delitos para justificación.",
        "Porque, si por un delito reinó la muerte por uno, mucho más reinarán en vida por un Jesucristo los que reciben la abundancia de la gracia, y del don de la justicia.",
        "Así que, de la manera que por un delito [vino la culpa] á todos los hombres para condenación, así por una justicia [vino la gracia] á todos los hombres para justificación de vida.",
        "Porque como por la desobediencia de un hombre los muchos fueron constituídos pecadores, así por la obediencia de uno los muchos serán constituídos justos.",
        "La ley empero entró para que el pecado creciese; mas cuando el pecado creció, sobrepujó la gracia;",
        "Para que, de la manera que el pecado reinó para muerte, así también la gracia reine por la justicia para vida eterna por Jesucristo Señor nuestro.",
    ),
    ("1 Corintios" to 13) to listOf(
        "Si yo hablase lenguas humanas y angélicas, y no tengo caridad, vengo á ser [como] metal que resuena, ó címbalo que retiñe.",
        "Y si tuviese profecía, y entendiese todos los misterios y toda ciencia; y si tuviese toda la fe, de tal manera que traspasase los montes, y no tengo caridad, nada soy.",
        "Y si repartiese toda mi hacienda para dar de comer [á pobres], y si entregase mi cuerpo para ser quemado, y no tengo caridad, de nada me sirve.",
        "La caridad es sufrida, es benigna; la caridad no tiene envidia, la caridad no hace sinrazón, no se ensancha;",
        "No es injuriosa, no busca lo suyo, no se irrita, no piensa el mal;",
        "No se huelga de la injusticia, mas se huelga de la verdad;",
        "Todo lo sufre, todo lo cree, todo lo espera, todo lo soporta.",
        "La caridad nunca deja de ser: mas las profecías se han de acabar, y cesarán las lenguas, y la ciencia ha de ser quitada;",
        "Porque en parte conocemos, y en parte profetizamos;",
        "Mas cuando venga lo que es perfecto, entonces lo que es en parte será quitado.",
        "Cuando yo era niño, hablaba como niño, pensaba como niño, juzgaba como niño; mas cuando ya fuí hombre hecho, dejé lo que era de niño.",
        "Ahora vemos por espejo, en obscuridad; mas entonces [veremos] cara á cara: ahora conozco en parte; mas entonces conoceré como soy conocido.",
        "Y ahora permanecen la fe, la esperanza, y la caridad, estas tres: empero la mayor de ellas es la caridad.",
    ),
    ("Filipenses" to 4) to listOf(
        "Así que, hermanos míos amados y deseados, gozo y corona mía, estad así firmes en el Señor, amados.",
        "A Euodias ruego, y á Syntychê exhorto, que sientan lo mismo en el Señor.",
        "Asimismo te ruego también á ti, hermano compañero, ayuda á las que trabajaron juntamente conmigo en el evangelio, con Clemente también, y los demás mis colaboradores, cuyos nombres están en el libro de la vida.",
        "Gozaos en el Señor siempre: otra vez digo: Que os gocéis.",
        "Vuestra modestia sea conocida de todos los hombres. El Señor está cerca.",
        "Por nada estéis afanosos; sino sean notorias vuestras peticiones delante de Dios en toda oración y ruego, con hacimiento de gracias.",
        "Y la paz de Dios, que sobrepuja todo entendimiento, guardará vuestros corazones y vuestros entendimientos en Cristo Jesús.",
        "Por lo demás, hermanos, todo lo que es verdadero, todo lo honesto, todo lo justo, todo lo puro, todo lo amable, todo lo que es de buen nombre; si hay virtud alguna, si alguna alabanza, en esto pensad.",
        "Lo que aprendisteis y recibisteis y oísteis y visteis en mí, esto haced; y el Dios de paz será con vosotros.",
        "Mas en gran manera me gocé en el Señor de que ya al fin ha reflorecido vuestro cuidado de mí; de lo cual aun estabais solícitos, pero os faltaba la oportunidad.",
        "No lo digo en razón de indigencia, pues he aprendido á contentarme con lo que tengo.",
        "Sé estar humillado, y sé tener abundancia: en todo y por todo estoy enseñado, así para hartura como para hambre, así para tener abundancia como para padecer necesidad.",
        "Todo lo puedo en Cristo que me fortalece.",
        "Sin embargo, bien hicisteis que comunicasteis juntamente á mi tribulación.",
        "Y sabéis también vosotros, oh Filipenses, que al principio del evangelio, cuando partí de Macedonia, ninguna iglesia me comunicó en razón de dar y recibir, sino vosotros solos.",
        "Porque aun á Tesalónica me enviasteis lo necesario una y dos veces.",
        "No porque busque dádivas; mas busco fruto que abunde en vuestra cuenta.",
        "Empero todo lo he recibido, y tengo abundancia: estoy lleno, habiendo recibido de Epafrodito lo que enviasteis, olor de suavidad, sacrificio acepto, agradable á Dios.",
        "Mi Dios, pues, suplirá todo lo que os falta conforme á sus riquezas en gloria en Cristo Jesús.",
        "Al Dios pues y Padre nuestro [sea] gloria por los siglos de los siglos. Amén.",
        "Saludad á todos los santos en Cristo Jesús. Los hermanos que están conmigo os saludan.",
        "Todos los santos os saludan, y mayormente los que son de casa de César.",
        "La gracia de nuestro Señor Jesucristo sea con todos vosotros. Amén. Escrita de Roma con Epafrodito.",
    ),
)
